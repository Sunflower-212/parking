"""HTTP and recovery smoke test for this project's running local Compose deployment."""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from urllib.error import HTTPError, URLError
import json
import os
import subprocess
import time
import uuid

root = Path(__file__).resolve().parents[1]
env = dict(line.split('=', 1) for line in (root / '.env').read_text(encoding='utf-8').splitlines() if line and not line.startswith('#') and '=' in line)
project = os.environ.get('PARKING_COMPOSE_PROJECT', 'parking')
if project not in ('parking', 'parking-verification'):
    raise SystemExit('Smoke tests support only parking or parking-verification')
compose_files = ['-f', 'compose.yml', '-f', 'compose.test.yml' if project == 'parking-verification' else 'compose.dev.yml']
base = 'http://127.0.0.1:' + ('28088' if project == 'parking-verification' else env.get('HTTP_PORT', '8088'))
prefix = 'sm' + uuid.uuid4().hex[:8]

def api(path, token=None, body=None, method='GET', form=False):
    headers = {}
    if token:
        headers['Authorization'] = token
    data = None
    if body is not None:
        data = (urlencode(body) if form else json.dumps(body)).encode('utf-8')
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    with urlopen(Request(base + '/api' + path, data=data, headers=headers, method=method), timeout=15) as response:
        result = json.load(response)
    if result.get('code') != 0:
        raise AssertionError(result.get('message'))
    return result.get('data')

def compose(*args):
    subprocess.run(['docker', 'compose', '-p', project, *compose_files, *args], cwd=root, check=True, stdout=subprocess.DEVNULL)

def sql(statement):
    command = ['docker', 'compose', '-p', project, *compose_files, 'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names parking_springboot']
    result = subprocess.run(command, input=statement, encoding='utf-8', cwd=root, capture_output=True, check=True)
    return result.stdout.strip()

def healthy():
    deadline = time.monotonic() + 100
    consecutive = 0
    while time.monotonic() < deadline:
        try:
            with urlopen(base + '/api/actuator/health', timeout=3) as response:
                if json.load(response).get('status') == 'UP':
                    consecutive += 1
                    if consecutive >= 3:
                        return
        except (HTTPError, URLError, TimeoutError, OSError):
            consecutive = 0
        time.sleep(1)
    raise AssertionError('Backend did not become healthy')

def await_expiry(token):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if not api('/enter/userEnterOrReservation', token):
            return
        time.sleep(.3)
    raise AssertionError('Reservation did not expire after recovery')

def recovered_user(token):
    deadline = time.monotonic() + 20
    while True:
        try:
            return api('/user/userInfo', token)
        except HTTPError as error:
            if error.code not in (502, 503, 504) or time.monotonic() > deadline:
                raise
            time.sleep(1)

healthy()
admin = api('/admin/login', body={'username': env['BOOTSTRAP_ADMIN_USERNAME'], 'password': env['BOOTSTRAP_ADMIN_PASSWORD']}, method='POST', form=True)
rule = park = user_id = None
try:
    api('/rule/create', admin, {'rule_name': prefix, 'free_time': 10, 'charging_unit': 30, 'unit_cost': 2.50}, 'POST')
    rule = api('/rule/select?rule_name=' + prefix, admin)[0]['id']
    admin_id = api('/admin/adminInfo', admin)['id']
    api('/parking/rootAddParking', admin, {'admin_id': admin_id, 'park_name': prefix, 'park_address': 'integration-test', 'park_storey': 1, 'rule_id': rule, 'park_mount': 999, 'park_spare': 999}, 'POST')
    park = next(p['id'] for p in api('/parking/list', admin) if p['park_name'] == prefix)
    for col in (1, 2):
        api('/spaces/addSpace', admin, {'park_name': prefix, 'spaces_name': 'A' + str(col), 'spaces_row': 1, 'spaces_col': col, 'spaces_storey': 1, 'spaces_flag': 0}, 'POST')
    api('/user/register', body={'username': prefix, 'password': 'test12345'}, method='POST', form=True)
    user = api('/user/login', body={'username': prefix, 'password': 'test12345'}, method='POST', form=True)
    user_id = api('/user/userInfo', user)['id']
    api('/user/update', user, {'id': 2147483647, 'name': '测试用户', 'plate_num': '京C-' + prefix[2:7].upper(), 'car_color': '黑色', 'tel_num': '139' + str(int(uuid.uuid4().hex[:8], 16))[-8:].zfill(8)}, 'PUT')
    assert api('/user/userInfo', user)['id'] == user_id
    with urlopen(Request(base + '/api/user/export?user_name=' + prefix, headers={'Authorization': admin}), timeout=15) as export:
        csv = export.read().decode('utf-8-sig')
        assert prefix in csv and '用户名' in csv and 'test12345' not in csv
        assert export.headers.get_content_type() == 'text/csv'
    spaces = api(f'/spaces/get?park_id={park}&spaces_storey=1', user)
    first = api('/enter/reservation', user, {'id': spaces[0]['id'], 'park_id': 2147483647}, 'PATCH')['id']
    assert api('/enter/userPageList?pageNum=1&pageSize=5', user)['total'] == 1
    assert api('/orders/adminPageList?pageNum=1&pageSize=5&admin_name=' + env['BOOTSTRAP_ADMIN_USERNAME'], admin)['total'] >= 0
    try:
        api('/enter/adminPageList?pageNum=1&pageSize=5', user)
        raise AssertionError('User accessed administrator route')
    except HTTPError as error:
        assert error.code == 403
    print('PASS: real HTTP login, profile ownership, map, reservation and role checks')
    compose('stop', 'backend')
    sql(f'UPDATE enter SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={first}; UPDATE reservation_event SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE reservation_id={first};')
    compose('start', 'backend')
    healthy()
    await_expiry(user)
    print('PASS: backend restart recovers an overdue reservation from the database outbox')
    second = api('/enter/reservation', user, {'id': spaces[1]['id']}, 'PATCH')['id']
    sql(f'UPDATE enter SET expires_at=DATE_ADD(NOW(),INTERVAL 4 SECOND) WHERE id={second}; UPDATE reservation_event SET expires_at=DATE_ADD(NOW(),INTERVAL 4 SECOND) WHERE reservation_id={second};')
    compose('restart', 'redis')
    healthy()
    await_expiry(user)
    print('PASS: Redis reconnect rebuilds pending delivery and expires the reservation')
    compose('restart', 'mysql', 'redis', 'backend')
    healthy()
    assert sql(f'SELECT COUNT(*) FROM enter WHERE user_id={user_id} AND reservation=2;') == '2'
    assert sql(f'SELECT park_spare FROM parking_capacity_total WHERE park_id={park};') == '2'
    assert recovered_user(user)['id'] == user_id
    print('PASS: MySQL records, Redis login and capacity survive container restarts')
finally:
    if user_id is not None:
        sql(f'DELETE FROM reservation_event WHERE reservation_id IN(SELECT id FROM enter WHERE user_id={user_id}); DELETE FROM enter WHERE user_id={user_id}; DELETE FROM user WHERE id={user_id};')
    if park is not None:
        sql(f'DELETE FROM spaces WHERE park_id={park}; DELETE FROM parking WHERE id={park};')
    if rule is not None:
        sql(f'DELETE FROM rule WHERE id={rule};')
