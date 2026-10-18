"""Back up and migrate a stopped Compose backend. Credentials remain inside the MySQL container."""
import argparse,gzip,subprocess,sys,time,uuid,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',required=True)
    parser.add_argument('--buckets',choices=[1,16],type=int,default=16)
    parser.add_argument('--rollback',action='store_true')
    args=parser.parse_args()
    if not re.fullmatch(r'parking(?:-[a-z0-9]+)*',args.project):parser.error('Expected a parking Compose project')
    running=subprocess.check_output(['docker','ps','-q','--filter',f'label=com.docker.compose.project={args.project}','--filter','label=com.docker.compose.service=backend'],cwd=ROOT).decode().strip()
    if running:raise SystemExit('Stop all backend writers before migration. No schema or data changed.')
    base=['docker','compose','-p',args.project,'-f','compose.yml','exec','-T','mysql','sh','-c']
    folder=ROOT/'.work/backups';folder.mkdir(parents=True,exist_ok=True)
    backup=folder/f'{args.project}-{time.strftime("%Y%m%d-%H%M%S")}-{uuid.uuid4().hex[:6]}.sql.gz'
    process=subprocess.Popen(base+['MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --no-tablespaces --single-transaction --routines --triggers parking_springboot'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    size=0
    with gzip.open(backup,'xb') as output:
        while data:=process.stdout.read(1024*1024):output.write(data);size+=len(data)
    error=process.stderr.read();code=process.wait()
    if code or size<100:raise SystemExit('Backup failed; migration was not started. Inspect the private backup and container logs.')
    print('Private backup saved: '+str(backup),flush=True)
    names=['database/rollback/004-capacity.sql'] if args.rollback else ['database/migrations/004-capacity.sql','database/migrations/005-background-tasks.sql','database/migrations/006-capacity-buckets.sql']
    for name in names:
        source=f'SET @capacity_buckets={args.buckets};\n'+(ROOT/name).read_text(encoding='utf-8')
        result=subprocess.run(base+['MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" --default-character-set=utf8mb4 parking_springboot'],cwd=ROOT,input=source.encode(),capture_output=True)
        if result.returncode:
            # SQL errors refer to repository statements, never echo connection credentials.
            print(result.stderr.decode(errors='replace')[-1500:],file=sys.stderr)
            raise SystemExit('Migration stopped. Keep writers stopped and inspect the error; do not clear business tables.')
        print('PASS: '+name,flush=True)
    print('Migration complete. Set CAPACITY_BUCKETS to the migrated value before starting the new backend.' if not args.rollback else 'Legacy counters restored. Inspect totals before starting the old image.')
