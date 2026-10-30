"""Verify payment migration twice against a disposable database, without provider calls."""
import argparse
from pathlib import Path
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--compose-file', default='compose.test.yml')
parser.add_argument('--env-file', default='.env')
parser.add_argument('--project', default='parking-verification')
args = parser.parse_args()
if not args.project.startswith('parking-') or 'verification' not in args.project:
    raise SystemExit('Use an isolated parking verification project')
root = Path(__file__).resolve().parents[1]
database = 'parking_payment_' + uuid.uuid4().hex[:12]
command = ['docker', 'compose', '--env-file', args.env_file, '-p', args.project, '-f', args.compose_file,
           'exec', '-T', 'mysql', 'sh', '-c',
           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names']


def sql(source):
    result = subprocess.run(command, input=source, text=True, encoding='utf-8', capture_output=True, cwd=root)
    if result.returncode:
        raise RuntimeError('Isolated migration failed; inspect the disposable database locally')
    return result.stdout.strip()


try:
    schema = (root / 'database/init/001-schema.sql').read_text(encoding='utf-8')
    sql(schema.replace('parking_springboot', database))
    sql(f'''USE {database}; DROP TABLE payment_event;
ALTER TABLE recharges DROP INDEX uk_recharge_merchant_order,DROP COLUMN merchant_order_no,
DROP COLUMN expires_at,DROP COLUMN payment_state,DROP COLUMN cancel_requested;
INSERT INTO user(user_name,password,balance,create_time,update_time) VALUES('payment-migration','test-only',20,NOW(),NOW());
INSERT INTO recharges(id,user_id,amounts,flag,pay_method,pay_no,create_time,pay_time) VALUES
(4,1,5,1,0,'test-paid',NOW(),NOW()),(7,1,10,2,0,'',NOW(),NULL);
''')
    migration = (root / 'database/migrations/008-payment-lifecycle.sql').read_text(encoding='utf-8').replace('parking_springboot', database)
    for _ in range(2):
        sql(migration)
        assert sql(f'USE {database}; SELECT CONCAT(flag,":",payment_state,":",merchant_order_no,":",expires_at IS NULL) FROM recharges WHERE id=7;') == '2:VERIFYING:7:1'
        assert sql(f'USE {database}; SELECT payment_state FROM recharges WHERE id=4;') == 'PAID'
        assert sql(f'USE {database}; SELECT balance FROM user WHERE id=1;') == '20.00'
    sql(f"USE {database}; UPDATE recharges SET merchant_order_no='PKnew',payment_state='CLOSING',expires_at=NOW() WHERE id=7;")
    sql(migration)
    assert sql(f'USE {database}; SELECT CONCAT(merchant_order_no,":",payment_state) FROM recharges WHERE id=7;') == 'PKnew:CLOSING'
    print('PASS: repeated payment upgrade preserves balances, payment facts, legacy deadlines and new merchant numbers')
finally:
    assert database.startswith('parking_payment_') and len(database) == 28
    sql(f'DROP DATABASE IF EXISTS `{database}`;')
