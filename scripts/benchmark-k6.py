"""Three-version benchmark confined to parking-benchmark. No real application data or secrets are exported."""
import argparse, json, os, re, subprocess, threading, time
from pathlib import Path
import benchmark as bench

ROOT=bench.ROOT
RESULTS=bench.WORK/'k6'
SERVICES=['backend','mysql','redis','redis-cache']
def root_sql(query):
    return bench.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names parking_springboot',input=query.encode()).decode()
def choose(version,local,index):
    # These helpers verify the synthetic fixture before cleaning only its transient bookings.
    if version=='original':bench.baseline()
    else:bench.upgrade()
    exists=bench.sql("select count(*) from information_schema.statistics where table_schema=database() and table_name='spaces' and index_name='idx_spaces_floor_state';").decode().splitlines()[-1]
    if exists!='0':bench.sql('alter table spaces drop index idx_spaces_floor_state;')
    if index:bench.sql('create index idx_spaces_floor_state on spaces(park_id,spaces_storey,id,spaces_flag);')
    values=bench.settings()
    image={'original':'parking-baseline','released':'parking-released','candidate':'parking'}[version]
    tag={'original':':797313c','released':':21d3012','candidate':':latest'}[version]
    values['BENCH_BACKEND_IMAGE']=image+'-backend'+tag
    values['BENCH_FRONTEND_IMAGE']=image+'-frontend'+tag
    values['BENCH_LOCAL_CACHE']='true' if local else 'false'
    bench.ENV.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
    bench.compose('up','-d','--wait','backend','frontend')
def digest():
    output=root_sql("SELECT DIGEST,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='parking_springboot' AND DIGEST_TEXT NOT LIKE '%events_statements_summary_by_digest%';")
    return {cells[0]:[int(cells[1]),int(cells[2])] for line in output.splitlines() if len(cells:=line.split('\t'))==3}
def redis_info(service):
    value=bench.compose('exec','-T',service,'sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli INFO commandstats').decode()
    return {line.split(':',1)[0]:int(line.split('calls=')[1].split(',')[0]) for line in value.splitlines() if 'calls=' in line}
def run(args):
    RESULTS.mkdir(exist_ok=True)
    bench.compose('up','-d','--wait','mysql','redis','redis-cache')
    choose(args.version,args.local,args.index)
    labels=args.label or args.version
    previous_metrics={}
    for round_no in range(1,args.rounds+1):
        if args.mode=='cold':bench.compose('exec','-T','redis-cache','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB')
        if args.mode=='warm':
            auth=bench.login('benchmark',bench.settings()['BENCH_ADMIN_PASSWORD'],True)
            bench.request('/orders/allData' if args.version=='original' else '/dashboard/summary',auth)
        # Snapshot server counters before setup too; report this small fixed overhead explicitly.
        before=digest();before_redis={s:redis_info(s) for s in ('redis','redis-cache')}
        business_date=root_sql('SELECT CURRENT_DATE;').strip()
        samples=[];stop=threading.Event();start=time.monotonic()
        def sample():
            while not stop.is_set():
                result=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}',*[f'parking-benchmark-{s}-1' for s in SERVICES], 'parking-benchmark-load'],capture_output=True)
                samples.append({'seconds':round(time.monotonic()-start,2),'services':[json.loads(line) for line in result.stdout.decode().splitlines()]})
                stop.wait(5)
        sampler=threading.Thread(target=sample,daemon=True);sampler.start()
        filename=f'{labels}-{round_no}.json'
        env={**os.environ,'BENCH_ADMIN_PASSWORD':bench.settings()['BENCH_ADMIN_PASSWORD']}
        profile=getattr(args,'profile','mixed')
        mounted=str(ROOT/'scripts')+':/scripts:ro' if profile in ('arrival','background') else str(ROOT/('scripts/benchmark-soak-k6.js' if profile=='soak' else 'scripts/benchmark-k6.js'))+':/scripts/benchmark.js:ro'
        entry='/scripts/benchmark-'+profile+'-k6.js' if profile in ('arrival','background') else '/scripts/benchmark.js'
        command=['docker','run','--rm','--name','parking-benchmark-load','--network','parking-benchmark_default','--memory','1g','--cpus','4',
            '-v',mounted,'-v',str(RESULTS)+':/results',
            '-e','BENCH_ADMIN_PASSWORD','-e','VERSION='+args.version,'-e','ROUND='+str(round_no),'-e','MODE='+args.mode,
            '-e','WARMUP='+str(args.warmup),'-e','SECONDS='+str(args.seconds),'-e','CLIENTS='+str(args.clients),'-e','PROFILE='+getattr(args,'profile','mixed'),
            '-e','RESULT_PATH=/results/'+filename,'grafana/k6:0.57.0','run','--quiet',entry]
        try:
            result=subprocess.run(command,cwd=ROOT,env=env,capture_output=True)
        finally:stop.set();sampler.join(timeout=10)
        # Keep teardown probes even if subsequent SQL/telemetry collection is interrupted.
        # These private files contain fixture telemetry and stay under ignored .work.
        (RESULTS/(filename+'.stdout')).write_bytes(result.stdout)
        (RESULTS/(filename+'.stderr')).write_bytes(result.stderr)
        (RESULTS/(filename+'.resources.json')).write_text(json.dumps(samples))
        after=digest();after_redis={s:redis_info(s) for s in ('redis','redis-cache')}
        if result.returncode:
            print(result.stdout.decode(errors='replace')[-3500:]);print(result.stderr.decode(errors='replace')[-1500:]);raise RuntimeError('k6 failed')
        data=json.loads((RESULTS/filename).read_text())
        if data.get('requests',0)<1 or args.mode=='mixed' and not {'dashboard','orders','floor','reservation','cancellation'}<=set(data['groups']):
            raise RuntimeError('Incomplete measured traffic; this run is invalid')
        data['backend_image_id']=subprocess.check_output(['docker','inspect','--format','{{.Image}}','parking-benchmark-backend-1']).decode().strip()
        data['business_date_start']=business_date
        data['business_date_end']=root_sql('SELECT CURRENT_DATE;').strip()
        expiry=re.findall(r'EXPIRY_PROBE=(\{.*\})',result.stdout.decode(errors='replace')+'\n'+result.stderr.decode(errors='replace'))
        if expiry:data['expiry_probe']=json.loads(expiry[-1].replace('\\"','"'))
        later=re.findall(r'LATER_COHORT_PROBE=(\{.*\})',result.stdout.decode(errors='replace')+'\n'+result.stderr.decode(errors='replace'))
        if later:data['later_expiry_probe']=json.loads(later[-1].replace('\\"','"'))
        export=re.findall(r'BACKGROUND_EXPORT=(\{.*\})',result.stdout.decode(errors='replace')+'\n'+result.stderr.decode(errors='replace'))
        if export:data['background_export_submission']=json.loads(export[-1].replace('\\"','"'))
        matches=re.findall(r'SERVER_METRICS=(\{.*\})',result.stdout.decode(errors='replace')+'\n'+result.stderr.decode(errors='replace'))
        if matches:
            current_metrics=json.loads(matches[-1].replace('\\"','"'))
            data['server_metrics_including_warmup_setup']={name:{stat:(value-previous_metrics.get(name,{}).get(stat,0) if stat in ('COUNT','TOTAL_TIME') else value) for stat,value in values.items()} for name,values in current_metrics.items()}
            previous_metrics=current_metrics
        data['resources']=samples
        data['sql_count_including_warmup_setup']=sum(v[0]-before.get(k,[0,0])[0] for k,v in after.items())
        data['sql_seconds_including_warmup_setup']=round(sum(v[1]-before.get(k,[0,0])[1] for k,v in after.items())/1e12,3)
        data['redis_commands_including_warmup_setup']={s:{key:value-before_redis[s].get(key,0) for key,value in values.items()} for s,values in after_redis.items()}
        (RESULTS/filename).write_text(json.dumps(data,indent=2))
        print(json.dumps({key:data[key] for key in ('version','round','requests','rps','errors','groups')}),flush=True)
        if data['errors'].get('rate',1)>0:raise RuntimeError('Unexpected business or transport errors; do not publish as a valid run')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--version',choices=['original','released','candidate'],required=True)
    parser.add_argument('--local',action='store_true');parser.add_argument('--index',action='store_true');parser.add_argument('--label')
    parser.add_argument('--clients',type=int,default=100);parser.add_argument('--warmup',type=int,default=120);parser.add_argument('--seconds',type=int,default=300);parser.add_argument('--rounds',type=int,default=3);parser.add_argument('--mode',choices=['mixed','cold','warm','floor'],default='mixed')
    run(parser.parse_args())
