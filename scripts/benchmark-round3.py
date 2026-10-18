"""Isolated repeatable capacity/admission experiment. Never targets the main deployment."""
import argparse, importlib.util, json, hashlib, secrets, re, time
from pathlib import Path
import benchmark as bench

spec=importlib.util.spec_from_file_location('k6runner',Path(__file__).with_name('benchmark-k6.py'))
k6=importlib.util.module_from_spec(spec);spec.loader.exec_module(k6)
k6.RESULTS=bench.WORK/'k6-round3'

def choose(args):
    bench.reset_runtime()
    # Only synthetic fixture rows: reset_runtime verifies the fixture identity and stops writers.
    bench.sql((bench.ROOT/'database/init/004-capacity.sql').read_text())
    bench.sql('delete from parking_capacity;')
    bench.sql((bench.ROOT/'database/migrations/004-capacity.sql').read_text())
    bench.sql((bench.ROOT/'database/migrations/005-background-tasks.sql').read_text())
    bench.sql(f'SET @capacity_buckets={args.buckets};\n'+(bench.ROOT/'database/migrations/006-capacity-buckets.sql').read_text())
    values=bench.settings()
    values.update(BENCH_BACKEND_IMAGE=args.image,BENCH_FRONTEND_IMAGE='parking-frontend:latest',BENCH_CACHE_ENABLED='true',BENCH_LOCAL_CACHE='true',BENCH_ADMISSION_PERMITS=str(args.permits),BENCH_CAPACITY_BUCKETS=str(args.buckets))
    bench.ENV.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
    bench.compose('exec','-T','redis-cache','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB')
    if args.background:bench.compose('up','-d','--wait','rabbitmq')
    bench.compose('up','-d','--wait','backend','frontend')

def enrich(args,script_hash):
    for round_no in range(1,args.rounds+1):
        path=k6.RESULTS/f'{args.label}-{round_no}.json'
        data=json.loads(path.read_text())
        data['experiment']={'image':args.image,'permits':args.permits,'capacity_buckets':args.buckets,'profile':args.profile,'background_enabled':args.background,'load_script_sha256':script_hash}
        if args.image in ('parking-round3-backend:final','parking-round3-backend:release'):
            sql=f"""SELECT COUNT(*) FROM parking p WHERE (SELECT COUNT(*) FROM parking_capacity c WHERE c.park_id=p.id)<>{args.buckets};
SELECT COUNT(*) FROM parking_capacity c LEFT JOIN (SELECT park_id,MOD(CRC32(CAST(id AS CHAR)),{args.buckets}) bucket_id,SUM(spaces_flag IN(0,1)) total,SUM(spaces_flag=0) available FROM spaces GROUP BY park_id,MOD(CRC32(CAST(id AS CHAR)),{args.buckets})) s ON s.park_id=c.park_id AND s.bucket_id=c.bucket_id WHERE c.park_mount<>COALESCE(s.total,0) OR c.park_spare<>COALESCE(s.available,0);
SELECT COUNT(*) FROM spaces s LEFT JOIN enter e ON e.id=s.active_enter_id WHERE (s.spaces_flag=1 AND (e.id IS NULL OR e.space_id<>s.id OR NOT(e.flag=1 OR e.flag=0 AND e.reservation=1))) OR (s.spaces_flag<>1 AND s.active_enter_id IS NOT NULL);
SELECT COUNT(*) FROM enter e LEFT JOIN spaces s ON s.id=e.space_id WHERE e.active_user_id IS NOT NULL AND (s.id IS NULL OR s.active_enter_id IS NULL OR s.active_enter_id<>e.id OR s.spaces_flag<>1);"""
            values=[int(n) for n in k6.root_sql(sql).strip().splitlines()]
            data['consistency']=dict(zip(('missing_capacity_buckets','capacity_mismatch','invalid_space_owner','invalid_active_entry'),values,strict=True))
        if args.profile=='soak':
            probe=k6.root_sql("SELECT COUNT(*),COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,e.expires_at,v.completed_at)/1000000),0) FROM enter e JOIN reservation_event v ON v.reservation_id=e.id WHERE e.id>1000000 AND e.user_id BETWEEN 1 AND 40 AND e.flag=0 AND e.reservation=2 AND e.reserved_at IS NOT NULL AND v.completed_at IS NOT NULL;").strip().split('\t')
            data['expiry_sql_probe']={'completed':int(probe[0]),'maximum_lateness_seconds':float(probe[1])}
        if args.profile=='background':
            submission=data.get('background_export_submission',{});job=submission.get('id','')
            if not re.fullmatch(r'[a-f0-9-]{36}',job):raise RuntimeError('Export submission missing or invalid')
            fields=k6.root_sql(f"SELECT status,COALESCE(TIMESTAMPDIFF(MICROSECOND,created_at,completed_at)/1000,-1),COALESCE(result_file,'') FROM background_event WHERE id='{job}';").strip().split('\t')
            if fields[0]!='DONE' or not re.fullmatch(r'[a-f0-9-]{36}\.csv',fields[2]):raise RuntimeError('Background export did not complete')
            sizes=bench.compose('exec','-T','backend','wc','-lc','/app/exports/'+fields[2]).decode().split()
            data['background_export_result']={'status':fields[0],'ready_ms':float(fields[1]),'csv_lines':int(sizes[0]),'csv_bytes':int(sizes[1])}
            if int(sizes[0])!=100001:raise RuntimeError('Export row count does not match the isolated 100000-user fixture')
        path.write_text(json.dumps(data,indent=2))
        if any(data.get('consistency',{}).values()):raise RuntimeError('Post-load consistency failed; retain all evidence and do not release')
        if args.profile=='soak' and (data.get('later_expiry_probe')!={'held':20,'released':20} or data['expiry_sql_probe']['completed']!=40):
            raise RuntimeError('Second real expiry cohort did not complete: retain results and investigate queue backlog')
    print(json.dumps({'label':args.label,'image':args.image,'permits':args.permits}),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--image',default='parking-round3-backend:final')
    parser.add_argument('--permits',type=int,default=8)
    parser.add_argument('--buckets',type=int,choices=[1,16],default=1)
    parser.add_argument('--profile',choices=['mixed','multi','contention','arrival','soak','background'],default='mixed')
    parser.add_argument('--background',action='store_true')
    parser.add_argument('--label',required=True)
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--warmup',type=int,default=120)
    parser.add_argument('--seconds',type=int,default=300)
    parser.add_argument('--clients',type=int,default=100)
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',args.label):parser.error('Use a short alphanumeric result label with hyphens or underscores')
    if any(k6.RESULTS.glob(args.label+'-*.json')):
        parser.error('Result label already exists. Archive the prior private evidence or choose a new label; results are never silently overwritten.')
    hold=bench.WORK/'round3-candidate-hold'
    if args.label=='final-full' and hold.exists():
        (bench.WORK/'round3-candidate-waiting.json').write_text(json.dumps({'waiting':True,'label':args.label}))
        print('Waiting for the verified final candidate image; no load is running.',flush=True)
        while hold.exists():time.sleep(2)
        (bench.WORK/'round3-candidate-waiting.json').write_text(json.dumps({'waiting':False,'label':args.label}))
    if args.profile=='soak' and args.seconds<3600:parser.error('Soak requires at least 3600 sampled seconds')
    if args.profile=='background' and not args.background:parser.error('Background export profile requires --background')
    if args.background:
        values=bench.settings();values.setdefault('BENCH_RABBIT_PASSWORD',secrets.token_hex(24))
        bench.ENV.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
        def background_compose(*arguments,input=None):
            return bench.command(['docker','compose','-p','parking-benchmark','--env-file',str(bench.ENV),'-f','compose.benchmark.yml','-f','compose.benchmark.background.yml','--profile','background',*arguments],input=input,stdout=bench.subprocess.PIPE,stderr=bench.subprocess.PIPE).stdout
        bench.compose=background_compose;k6.SERVICES.append('rabbitmq')
    args.version='candidate';args.local=True;args.index=False;args.mode='mixed'
    k6.choose=lambda *unused:choose(args)
    load_script=bench.ROOT/('scripts/benchmark-'+args.profile+'-k6.js' if args.profile in ('soak','arrival','background') else 'scripts/benchmark-k6.js')
    def fingerprint():
        source=load_script.read_bytes()
        if args.profile in ('arrival','background'):source+=b'\0'+(bench.ROOT/'scripts/benchmark-k6.js').read_bytes()
        return hashlib.sha256(source).hexdigest()
    script_hash=fingerprint()
    # Parse with the actual fixed k6 runtime before any database reset or HTTP setup.
    check=bench.subprocess.run(['docker','run','--rm','--network','none','-v',str(bench.ROOT/'scripts')+':/scripts:ro',
        '-e','PROFILE='+args.profile,'-e','SECONDS='+str(args.seconds),'-e','WARMUP='+str(args.warmup),'-e','CLIENTS='+str(args.clients),
        'grafana/k6:0.57.0','inspect','--include-system-env-vars','/scripts/'+load_script.name],capture_output=True,cwd=bench.ROOT)
    if check.returncode:raise RuntimeError('k6 preflight failed: '+check.stderr.decode(errors='replace')[-1500:])
    k6.run(args)
    if script_hash!=fingerprint():
        raise RuntimeError('Load script changed during experiment; retain raw output but rerun before claiming comparable results')
    enrich(args,script_hash)
