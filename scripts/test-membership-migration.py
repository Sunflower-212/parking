"""Verify membership migration twice against a disposable database, without provider calls."""
import argparse
from pathlib import Path
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--compose-file', default='docker/compose.test.yml')
parser.add_argument('--env-file', default='.env')
parser.add_argument('--project', default='parking-verification')
args = parser.parse_args()
if not args.project.startswith('parking-') or 'verification' not in args.project:
    raise SystemExit('Use an isolated parking verification project')
root = Path(__file__).resolve().parents[1]
database = 'parking_member_' + uuid.uuid4().hex[:12]
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
    sql(f'''USE {database};
ALTER TABLE recharges DROP FOREIGN KEY fk_recharge_membership, DROP INDEX uk_membership_record,
DROP COLUMN membership_order_id, DROP COLUMN description, DROP COLUMN business_type;
DROP TABLE membership_order;
ALTER TABLE user DROP CHECK ck_user_reserved_balance, DROP COLUMN reserved_balance;
INSERT INTO user(user_name,password,balance,status,vip_start_time,vip_end_time,create_time,update_time)
VALUES('member-migration','test-only',100,1,'2026-01-01','2026-12-01',NOW(),NOW());
INSERT INTO recharges(user_id,amounts,flag,pay_method,create_time) VALUES(1,100,1,0,NOW());
''')
    migration = (root / 'database/migrations/009-membership-purchase.sql').read_text(encoding='utf-8').replace('parking_springboot', database)
    for _ in range(2):
        sql(migration)
        assert sql(f'USE {database}; SELECT CONCAT(balance,":",reserved_balance,":",DATE(vip_end_time)) FROM user WHERE id=1;') == '100.00:0.00:2026-12-01'
        assert sql(f'USE {database}; SELECT CONCAT(business_type,":",description) FROM recharges WHERE id=1;') == 'NORMAL_RECHARGE:普通充值'
    order = str(uuid.uuid4())
    sql(f'''USE {database};
INSERT INTO membership_order(id,user_id,request_key,plan_code,plan_name,months,price,reserved_amount,supplement_amount,created_at)
VALUES('{order}',1,'migration-request-key','MONTHLY','月度',1,188,100,88,NOW());
UPDATE user SET reserved_balance=100 WHERE id=1;
INSERT INTO recharges(user_id,amounts,pay_method,create_time,business_type,description,membership_order_id)
VALUES(1,88,0,NOW(),'MEMBERSHIP_TOPUP','会员充值补充（月度）','{order}');
''')
    sql(migration)
    assert sql(f'USE {database}; SELECT reserved_balance FROM user WHERE id=1;') == '100.00'
    assert sql(f'USE {database}; SELECT description FROM recharges WHERE membership_order_id="{order}";') == '会员充值补充（月度）'
    for statement in (
        "INSERT INTO membership_order(id,user_id,request_key,plan_code,plan_name,months,price,reserved_amount,supplement_amount,created_at) VALUES('other-order',1,'another-request-key','MONTHLY','月度',1,188,100,88,NOW())",
        f"INSERT INTO recharges(user_id,amounts,pay_method,create_time,business_type,description,membership_order_id) VALUES(1,88,0,NOW(),'MEMBERSHIP_TOPUP','duplicate','{order}')",
        'UPDATE user SET balance=99 WHERE id=1',
    ):
        try: sql(f'USE {database}; '+statement+';')
        except RuntimeError: pass
        else: raise AssertionError('Membership integrity constraint did not reject conflicting data')
    print('PASS: repeatable membership migration, legacy values, reservations, and uniqueness constraints')
finally:
    assert database.startswith('parking_member_') and len(database) == 27
    sql(f'DROP DATABASE IF EXISTS `{database}`;')
