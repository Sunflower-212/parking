"""Check the AI title migration in a disposable schema of an isolated test project."""
import argparse
from pathlib import Path
import subprocess
import uuid

p = argparse.ArgumentParser()
p.add_argument('--compose-file', default='compose.test.yml')
p.add_argument('--project', default='parking-verification')
args = p.parse_args()
if not args.project.startswith('parking-') or 'verification' not in args.project:
    raise SystemExit('An isolated parking verification project is required')
root = Path(__file__).resolve().parents[1]
database = 'parking_ai_' + uuid.uuid4().hex[:12]
command = ['docker', 'compose', '-p', args.project, '-f', args.compose_file,
           'exec', '-T', 'mysql', 'sh', '-c',
           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B']

def sql(source):
    result = subprocess.run(command, input=source, text=True, encoding='utf-8', capture_output=True, cwd=root)
    if result.returncode:
        raise RuntimeError('Isolated AI migration failed')
    return result.stdout.strip()

try:
    sql(f'''CREATE DATABASE {database}; USE {database};
CREATE TABLE historicalSession(id bigint unsigned primary key auto_increment,
chatId varchar(255) not null unique,type varchar(255) not null,userId varchar(255) not null,
create_time datetime not null,update_time datetime not null);
INSERT INTO historicalSession(chatId,type,userId,create_time,update_time)
VALUES('legacy','chat','7','2026-11-01 10:00:00','2026-11-01 10:01:00');''')
    migration = (root/'database/migrations/010-ai-session-metadata.sql').read_text(encoding='utf-8').replace('parking_springboot', database)
    for _ in range(2):
        sql(migration)
        assert sql(f"USE {database}; SELECT CONCAT(chatId,':',userId,':',title IS NULL,':',update_time) FROM historicalSession;") == 'legacy:7:1:2026-11-01 10:01:00'
    sql(f"USE {database}; UPDATE historicalSession SET title='停车场经营情况' WHERE chatId='legacy';")
    sql(migration)
    assert sql(f"USE {database}; SELECT title FROM historicalSession;") == '停车场经营情况'
    print('PASS: repeated AI migration preserves legacy rows and existing titles')
finally:
    sql(f'DROP DATABASE IF EXISTS {database};')
