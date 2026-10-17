"""Fault/restart tests confined to parking-verification (never the default application)."""
import json,subprocess,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
from urllib.parse import urlencode

ROOT=Path(__file__).resolve().parents[1]
credentials=dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
COMPOSE=['docker','compose','-p','parking-verification','-f','compose.yml','-f','compose.test.yml']
def service(*args):subprocess.run([*COMPOSE,*args],cwd=ROOT,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
def request(path,token=None,method='GET',body=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']=token
    req=Request('http://127.0.0.1:28080'+path,data=None if body is None else json.dumps(body).encode(),method=method,headers=headers)
    try:
        with urlopen(req,timeout=12) as response:return response.status,json.load(response)
    except HTTPError as exc:return exc.code,json.load(exc)
def wait_ready(token=None):
    for _ in range(60):
        try:
            status,body=request('/system/capabilities' if token else '/actuator/health',token)
            if status==200 and (body.get('code')==0 if token else body.get('status')=='UP'):return
        except (OSError,URLError):pass
        time.sleep(1)
    raise AssertionError('Verification service did not recover')
login=Request('http://127.0.0.1:28080/admin/login',data=urlencode({'username':credentials['BOOTSTRAP_ADMIN_USERNAME'],'password':credentials['BOOTSTRAP_ADMIN_PASSWORD']}).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
with urlopen(login,timeout=10) as response:token=json.load(response)['data']
name='recovery'+uuid.uuid4().hex[:7]
status,result=request('/user/addUser',token,'POST',{'user_name':name,'name':'恢复测试'})
assert status==200 and result['code']==0
try:
    assert request('/dashboard/summary',token)[1]['code']==0
    service('stop','redis-cache')
    assert request('/dashboard/summary',token)[1]['code']==0
    assert request('/system/capabilities',token)[1]['code']==0
    print('PASS: cache outage falls back to MySQL; same login token remains valid',flush=True)
    service('start','redis-cache')
    service('stop','redis')
    assert request('/system/capabilities',token)[0]==503
    service('start','redis');wait_ready(token)
    print('PASS: session Redis outage returns 503; AOF restart restores the same token',flush=True)
    service('restart','mysql');wait_ready(token)
    # Readiness of token Redis does not imply readiness of the DB; wait for full health too.
    wait_ready()
    result=request('/user/pageList?'+urlencode({'pageNum':1,'pageSize':20,'user_name':name}),token)[1]
    assert result['code']==0 and result['data']['total']==1
    service('restart','backend','frontend');wait_ready(token);wait_ready()
    assert request('/user/pageList?'+urlencode({'pageNum':1,'pageSize':20,'user_name':name}),token)[1]['data']['total']==1
    print('PASS: MySQL and application restarts preserve fixture data and login state',flush=True)
finally:
    service('start','redis','redis-cache','mysql','backend','frontend')
    wait_ready(token)
    request('/user/delete?'+urlencode({'user_name':name}),token,'DELETE')
