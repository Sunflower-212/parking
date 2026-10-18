"""Exercise upgrades in disposable databases inside this project's MySQL container."""
from pathlib import Path
import subprocess
import uuid
import os

root = Path(__file__).resolve().parents[1]
command = ['docker', 'compose', '-p', os.environ.get('PARKING_COMPOSE_PROJECT','parking-verification'), 'exec', '-T', 'mysql', 'sh', '-c',
           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names']

def sql(text, check=True):
    result = subprocess.run(command, input=text, encoding='utf-8', capture_output=True, cwd=root)
    if check and result.returncode:
        raise RuntimeError(result.stderr)
    return result

schema = (root / 'database/init/001-schema.sql').read_text(encoding='utf-8')
migration = (root / 'database/migrations/002-reservation-queue.sql').read_text(encoding='utf-8')
performance = (root / 'database/migrations/003-performance-indexes.sql').read_text(encoding='utf-8')
for ambiguous in (False, True):
    database = 'parking_migration_' + uuid.uuid4().hex[:12]
    try:
        sql(schema.replace('parking_springboot', database))
        sql(f'''USE {database};
DROP TABLE reservation_event;
ALTER TABLE enter DROP INDEX uk_enter_active_user,DROP INDEX uk_enter_active_space,DROP INDEX idx_enter_pending,
DROP COLUMN active_user_id,DROP COLUMN active_space_id,DROP COLUMN space_id,DROP COLUMN reserved_at,DROP COLUMN expires_at;
ALTER TABLE spaces DROP INDEX uk_space_entry,DROP COLUMN active_enter_id;
ALTER TABLE user MODIFY password varchar(32) NOT NULL;
ALTER TABLE admin MODIFY password varchar(32) NOT NULL;
INSERT INTO admin(admin_name,password,permissions,create_time,update_time) VALUES('migration-admin',MD5('test-only'),1,NOW(),NOW());
INSERT INTO user(user_name,password,plate_num,create_time,update_time) VALUES('migration-user',MD5('test-only'),'京A-12345',NOW(),NOW());
INSERT INTO rule(rule_name,free_time,charging_unit,unit_cost,create_time,update_time) VALUES('migration-rule',0,30,2.50,NOW(),NOW());
INSERT INTO parking(admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) VALUES(1,'migration-park','test',2,1,1,1,NOW(),NOW());
INSERT INTO spaces(park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,user_name,create_time,update_time) VALUES(1,'A1',1,1,1,1,'migration-user',NOW(),NOW());
INSERT INTO enter(plate_num,user_id,park_id,enter_time,flag,reservation) VALUES('京A-12345',1,1,NOW(),0,1);
''')
        if ambiguous:
            sql(f"USE {database}; INSERT INTO spaces(park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,user_name,create_time,update_time) VALUES(1,'A2',2,1,1,1,'migration-user',NOW(),NOW());")
        result = sql(f'USE {database};\n' + migration, check=not ambiguous)
        if ambiguous:
            assert result.returncode != 0, 'Ambiguous migration must stop'
            assert int(sql(f"USE {database}; SELECT COUNT(*) FROM migration_report;").stdout.strip()) > 0
            assert sql(f"USE {database}; SELECT COUNT(*) FROM spaces WHERE spaces_flag=1;").stdout.strip() == '2'
            print('PASS: ambiguous historical reservation reported, occupied spaces preserved')
        else:
            assert sql(f"USE {database}; SELECT CONCAT(space_id,':',active_user_id) FROM enter WHERE id=1;").stdout.strip() == '1:1'
            assert sql(f"USE {database}; SELECT COUNT(*) FROM reservation_event;").stdout.strip() == '1'
            sql(f'USE {database};\n' + migration)
            sql(performance.replace('parking_springboot',database))
            sql(performance.replace('parking_springboot',database))
            assert sql(f"USE {database}; SELECT COUNT(*) FROM orders;").stdout.strip() == '0'
            assert sql(f"USE {database}; SELECT COUNT(*) FROM enter;").stdout.strip() == '1'
            assert sql(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='{database}' AND table_name='orders' AND index_name='idx_orders_user_time';").stdout.strip() == '3'
            sql(f'USE {database}; ALTER TABLE orders RENAME INDEX idx_orders_user_time TO migration_equivalent_user_time;')
            sql(performance.replace('parking_springboot',database))
            assert sql(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='{database}' AND table_name='orders' AND index_name='idx_orders_user_time';").stdout.strip() == '0'
            assert sql(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='{database}' AND table_name='orders' AND index_name='migration_equivalent_user_time';").stdout.strip() == '3'
            print('PASS: legacy data preserved, association and outbox backfilled, rerun succeeds')
            print('PASS: equivalent index with a different name is reused')
            for name in ('004-capacity.sql','005-background-tasks.sql','006-capacity-buckets.sql'):
                source=(root/'database/migrations'/name).read_text(encoding='utf-8')
                sql(f'USE {database};\n'+source);sql(f'USE {database};\n'+source)
            buckets=(root/'database/migrations/006-capacity-buckets.sql').read_text(encoding='utf-8')
            for count in (16,16,1):
                sql(f'USE {database}; SET @capacity_buckets={count};\n'+buckets)
                assert sql(f'USE {database}; SELECT COUNT(*) FROM parking_capacity;').stdout.strip()==str(count)
                assert sql(f'USE {database}; SELECT CONCAT(park_mount,\':\',park_spare) FROM parking_capacity_total;').stdout.strip()=='1:0'
            sql(f'USE {database};\n'+(root/'database/rollback/004-capacity.sql').read_text(encoding='utf-8'))
            assert sql(f'USE {database}; SELECT buckets FROM capacity_settings;').stdout.strip()=='0'
            sql(f'USE {database}; UPDATE enter SET reservation=2 WHERE id=1; UPDATE spaces SET spaces_flag=0,active_enter_id=NULL WHERE id=1; UPDATE parking SET park_spare=1 WHERE id=1;')
            sql(f'USE {database};\n'+(root/'database/migrations/004-capacity.sql').read_text(encoding='utf-8'))
            sql(f'USE {database};\n'+buckets)
            assert sql(f'USE {database}; SELECT park_spare FROM parking_capacity_total;').stdout.strip()=='1'
            sql(f'USE {database}; UPDATE parking_capacity SET park_spare=0;')
            assert sql(f'USE {database};\n'+buckets,check=False).returncode!=0,'Mismatched capacity must stop migration'
            assert sql(f'USE {database}; SELECT COUNT(*) FROM enter;').stdout.strip()=='1'
            print('PASS: capacity migration reruns, bucket switches preserve totals, mismatch blocks migration')
            print('PASS: rollback fences new writers; subsequent legacy writes can be safely re-upgraded')
    finally:
        # Generated name always stays within this script's disposable namespace.
        assert database.startswith('parking_migration_') and len(database) == 30
        sql(f'DROP DATABASE IF EXISTS `{database}`;')

database = 'parking_migration_' + uuid.uuid4().hex[:12]
try:
    for source in sorted((root / 'database/init').glob('*.sql')):
        sql(f'CREATE DATABASE IF NOT EXISTS `{database}`; USE `{database}`;\n' + source.read_text(encoding='utf-8').replace('parking_springboot',database))
    assert sql(f'USE {database}; SELECT buckets FROM capacity_settings WHERE id=1;').stdout.strip() == '16'
    assert sql(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{database}' AND table_name IN ('parking_capacity','parking_capacity_total','background_event','admin_audit');").stdout.strip() == '4'
    # A freshly initialized database can immediately follow the supported upgrade path.
    for name in ('004-capacity.sql','005-background-tasks.sql','006-capacity-buckets.sql'):
        sql(f'USE {database};\n'+(root/'database/migrations'/name).read_text(encoding='utf-8'))
    assert sql(f'USE {database}; SELECT buckets FROM capacity_settings WHERE id=1;').stdout.strip() == '16'
    print('PASS: complete fresh initialization uses 16 buckets and accepts the incremental upgrade path')
finally:
    assert database.startswith('parking_migration_') and len(database) == 30
    sql(f'DROP DATABASE IF EXISTS `{database}`;')
