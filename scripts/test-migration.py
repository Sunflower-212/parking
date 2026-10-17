"""Exercise upgrades in disposable databases inside this project's MySQL container."""
from pathlib import Path
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
command = ['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names']

def sql(text, check=True):
    result = subprocess.run(command, input=text, encoding='utf-8', capture_output=True, cwd=root)
    if check and result.returncode:
        raise RuntimeError(result.stderr)
    return result

schema = (root / 'database/init/001-schema.sql').read_text(encoding='utf-8')
migration = (root / 'database/migrations/002-reservation-queue.sql').read_text(encoding='utf-8')
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
            print('PASS: legacy data preserved, association and outbox backfilled, rerun succeeds')
    finally:
        # Generated name always stays within this script's disposable namespace.
        assert database.startswith('parking_migration_') and len(database) == 30
        sql(f'DROP DATABASE IF EXISTS `{database}`;')
