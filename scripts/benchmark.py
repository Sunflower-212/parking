"""Isolated, deterministic performance fixture and HTTP benchmark. Never targets the main stack.

prepare -> seed -> run --label baseline -> upgrade -> run --label optimized
Defaults implement 100 clients, 120s warmup + 300s measurement, 3 rounds.
Results contain only timings, counts and resource usage, never credentials or responses.
"""
import argparse, concurrent.futures, hashlib, http.client, json, os, random, secrets, statistics, subprocess, threading, time, zipfile
from pathlib import Path
from urllib.parse import urlencode

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.work'
ENV=WORK/'benchmark.env'
def command(args,**kwargs):
    try:return subprocess.run(args,cwd=ROOT,check=True,**kwargs)
    except subprocess.CalledProcessError as exc:
        if exc.stderr:print(exc.stderr.decode(errors='replace')[-1800:])
        raise
def compose(*args,input=None):
    return command(['docker','compose','-p','parking-benchmark','--env-file',str(ENV),'-f','compose.benchmark.yml',*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
def sql(text):
    return compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" --batch --raw parking_springboot',input=text.encode())
def settings():
    return dict(line.split('=',1) for line in ENV.read_text().splitlines() if '=' in line and not line.startswith('#'))
connections=threading.local()
def request(path,token=None,method='GET',body=None):
    conn=getattr(connections,'http',None)
    if conn is None:
        conn=http.client.HTTPConnection('127.0.0.1',18080,timeout=30);connections.http=conn
    headers={}
    if token:headers['Authorization']=token
    if isinstance(body,dict):headers['Content-Type']='application/json';body=json.dumps(body)
    elif body is not None:headers['Content-Type']='application/x-www-form-urlencoded'
    start=time.perf_counter()
    try:
        conn.request(method,path,body,headers);response=conn.getresponse();payload=response.read();elapsed=(time.perf_counter()-start)*1000
        return response.status,json.loads(payload),elapsed
    except Exception:
        conn.close();connections.http=None;raise
def login(name,password,admin=False):
    status,result,_=request('/admin/login' if admin else '/user/login',method='POST',body=urlencode({'username':name,'password':password}))
    if status!=200 or result.get('code')!=0:raise RuntimeError('Benchmark login failed')
    return result['data']
def prepare():
    WORK.mkdir(exist_ok=True)
    if not ENV.exists():ENV.write_text('\n'.join(f'{key}={secrets.token_hex(16 if key=="BENCH_ADMIN_PASSWORD" else 24)[:16] if key=="BENCH_ADMIN_PASSWORD" else secrets.token_hex(24)}' for key in ['BENCH_ROOT_PASSWORD','BENCH_DB_PASSWORD','BENCH_REDIS_PASSWORD','BENCH_CACHE_PASSWORD','BENCH_JWT_SECRET','BENCH_ADMIN_PASSWORD'])+'\n')
    folder=WORK/'benchmark-init';folder.mkdir(exist_ok=True)
    source=command(['git','show','797313c:database/init/001-schema.sql'],stdout=subprocess.PIPE).stdout
    (folder/'001-schema.sql').write_bytes(source)
    baseline_source=WORK/'baseline-source'
    if not baseline_source.exists():
        archive=WORK/'baseline-source.zip'
        command(['git','archive','--format=zip','--output',str(archive),'797313c'])
        baseline_source.mkdir()
        with zipfile.ZipFile(archive) as contents:
            for name in contents.namelist():
                if not (baseline_source/name).resolve().is_relative_to(baseline_source.resolve()):raise RuntimeError('Unexpected archive path')
            contents.extractall(baseline_source)
    print('Isolated benchmark environment prepared; secrets remain in .work')
def seed():
    # Explicit guard: refuse to seed an existing environment; no truncation or destructive reset.
    if sql('select count(*) n from user;').decode().strip().splitlines()[-1]!='0':raise RuntimeError('Benchmark database is not empty; refusing to seed')
    password=hashlib.md5(b'test12345').hexdigest()
    query=f'''
CREATE TABLE IF NOT EXISTS benchmark_digits(n INT PRIMARY KEY); INSERT IGNORE INTO benchmark_digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE seq(n INT PRIMARY KEY) AS SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n+1 n FROM benchmark_digits a CROSS JOIN benchmark_digits b CROSS JOIN benchmark_digits c CROSS JOIN benchmark_digits d CROSS JOIN benchmark_digits e CROSS JOIN benchmark_digits f;
INSERT INTO user(id,user_name,password,plate_num,balance,create_time,update_time) SELECT n,concat('bench',lpad(n,6,'0')),'{password}',concat('京A-',lpad(n,6,'0')),10000,date_sub(current_date,interval mod(n,365) day),now() FROM seq WHERE n<=100000;
INSERT INTO rule(id,rule_name,free_time,charging_unit,unit_cost,create_time,update_time) VALUES(1,'benchmark',10,30,2.50,now(),now());
INSERT INTO parking(id,admin_id,park_name,park_address,park_mount,park_spare,park_storey,rule_id,create_time,update_time) SELECT n,(SELECT min(id) FROM admin),concat('benchmark-',n),'synthetic',1000,1000,1,1,now(),now() FROM seq WHERE n<=100;
INSERT INTO spaces(id,park_id,spaces_name,spaces_col,spaces_row,spaces_storey,spaces_flag,create_time,update_time) SELECT n,floor((n-1)/1000)+1,concat('S',mod(n-1,1000)+1),mod(n-1,20)+1,mod(floor((n-1)/20),50)+1,1,0,now(),now() FROM seq WHERE n<=100000;
INSERT INTO enter(plate_num,user_id,park_id,space_id,enter_time,flag,reservation,update_time) SELECT concat('京A-',lpad(mod(n-1,100000)+1,6,'0')),mod(n-1,100000)+1,mod(n-1,100)+1,null,date_sub(now(),interval mod(n*7919,31536000) second),if(mod(n,10)=0,0,2),if(mod(n,10)=0,2,0),now() FROM seq;
INSERT INTO orders(plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time) SELECT concat('京A-',lpad(mod(n-1,100000)+1,6,'0')),mod(n-1,100000)+1,mod(n-1,100)+1,date_sub(now(),interval mod(n*7919,31536000)+3600 second),date_sub(now(),interval mod(n*7919,31536000) second),60,5,date_sub(now(),interval mod(n*7919,31536000) second) FROM seq;
INSERT INTO recharges(user_id,amounts,flag,pay_method,create_time,pay_time) SELECT mod(n-1,100000)+1,100,if(mod(n,5)=0,2,1),0,date_sub(now(),interval mod(n*7919,31536000) second),date_sub(now(),interval mod(n*7919,31536000) second) FROM seq WHERE n<=200000;
'''
    sql(query)
    print('Seed complete: 100k users, 100 lots, 100k spaces, 1m entries, 1m orders, 200k recharges')
def percentile(values,p):
    return round(sorted(values)[min(len(values)-1,int((len(values)-1)*p))],2) if values else None
def benchmark(args):
    for attempt in range(60):
        try:
            status,health,_=request('/actuator/health')
            if status==200 and health.get('status')=='UP':break
        except (OSError,http.client.HTTPException):pass
        time.sleep(1)
    else:raise RuntimeError('Benchmark backend did not become healthy')
    credentials=settings();admin=login('benchmark',credentials['BENCH_ADMIN_PASSWORD'],True)
    tokens=[login(f'bench{i+1:06d}','test12345') for i in range(args.clients)]
    optimized=args.label=='optimized'
    results=[];resources=[];stop_resources=threading.Event()
    def sample_resources():
        while not stop_resources.is_set():
            try:
                output=command(['docker','stats','--no-stream','--format','{{json .}}','parking-benchmark-backend-1','parking-benchmark-mysql-1','parking-benchmark-redis-1','parking-benchmark-redis-cache-1'],stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
                resources.append({'elapsed_seconds':round(time.monotonic()-resource_start,1),'services':[json.loads(line) for line in output.decode().splitlines()]})
            except Exception:pass
            stop_resources.wait(5)
    resource_start=time.monotonic();sampler=threading.Thread(target=sample_resources,daemon=True);sampler.start()
    for round_no in range(args.rounds):
        start=time.monotonic();measure=start+args.warmup;end=measure+args.seconds
        def worker(index):
            rng=random.Random(20261017+index+round_no*1000);buckets={};errors={};conflicts=0;latencies=[]
            # Each client uses a dedicated user and space; actual contention is tested separately.
            token=tokens[index];space=index+1
            while time.monotonic()<end:
                choice=rng.random();group=''
                try:
                    if choice<0.2:
                        group='dashboard';path='/dashboard/summary' if optimized else '/orders/allData';status,data,elapsed=request(path,admin)
                    elif choice<0.5:
                        group='orders';path='/orders/userPageList?pageNum=1&pageSize=20' if optimized else '/orders/userList';status,data,elapsed=request(path,token)
                    elif choice<0.8:
                        group='floor';path='/spaces/availability?park_id=1&spaces_storey=1' if optimized else '/spaces/get?park_id=1&spaces_storey=1';status,data,elapsed=request(path,token)
                    else:
                        group='reservation'
                        status,data,elapsed=request('/enter/reservation',token,'PATCH',{'id':space})
                        if status==200 and data.get('code')==0:
                            rid=data['data']['id'];status2,data2,cancel_time=request('/enter/cancelReservation?id='+str(rid),token,'POST')
                            if time.monotonic()>=measure:buckets.setdefault('cancellation',[]).append(cancel_time)
                            if status2!=200 or data2.get('code')!=0:errors['cancel_failed']=errors.get('cancel_failed',0)+1
                    if time.monotonic()>=measure:
                        buckets.setdefault(group,[]).append(elapsed)
                        if status!=200 or data.get('code')!=0:
                            errors[str(status)+':'+group]=errors.get(str(status)+':'+group,0)+1
                except Exception as exc:
                    if time.monotonic()>=measure:
                        key=type(exc).__name__+'_'+str(getattr(exc,'winerror',getattr(exc,'errno','')))+':'+group;errors[key]=errors.get(key,0)+1
                    time.sleep(.05)
            connection=getattr(connections,'http',None)
            if connection:connection.close();connections.http=None
            return buckets,errors
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.clients) as pool:outputs=list(pool.map(worker,range(args.clients)))
        merged={};errors={}
        for buckets,failed in outputs:
            for key,values in buckets.items():merged.setdefault(key,[]).extend(values)
            for key,value in failed.items():errors[key]=errors.get(key,0)+value
        total=sum(map(len,merged.values()));result={'round':round_no+1,'requests':total,'rps':round(total/args.seconds,2),'errors':errors,'groups':{k:{'count':len(v),'p50_ms':percentile(v,.5),'p95_ms':percentile(v,.95),'p99_ms':percentile(v,.99)} for k,v in merged.items()}}
        results.append(result);print(json.dumps({'label':args.label,**result}),flush=True)
        (WORK/f'benchmark-{args.label}{args.suffix}.json').write_text(json.dumps({'label':args.label,'clients':args.clients,'warmup_seconds':args.warmup,'sample_seconds':args.seconds,'rounds':results,'resource_samples':resources},indent=2))
    stop_resources.set();sampler.join(timeout=10)
def reset_runtime():
    compose('stop','backend','frontend')
    counts=sql("select count(*) from user where user_name like 'bench%'; select count(*) from user;").decode().strip().splitlines()
    if counts[-1]!='100000' or counts[1]!='100000':raise RuntimeError('Fixture identity mismatch; refusing to clean benchmark transactions')
    sql('update spaces set spaces_flag=0,active_enter_id=null,user_name=null where active_enter_id>1000000; delete from reservation_event where reservation_id>1000000; delete from enter where id>1000000; alter table enter auto_increment=1000001; update parking p set park_spare=(select count(*) from spaces s where s.park_id=p.id and s.spaces_flag=0);')
    compose('exec','-T','redis','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB')
def upgrade():
    reset_runtime()
    sql((ROOT/'database/migrations/003-performance-indexes.sql').read_text(encoding='utf-8'))
    values=settings();values['BENCH_BACKEND_IMAGE']='parking-backend';values['BENCH_FRONTEND_IMAGE']='parking-frontend';values['BENCH_CACHE_ENABLED']='true';ENV.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
    compose('up','-d','--wait','backend','frontend');print('Benchmark stack upgraded; seeded data and resource settings retained')
def baseline():
    reset_runtime()
    # MySQL may remove an implicit foreign-key index when a covering index is added.
    # Recreate the original single-column indexes before dropping performance indexes.
    for table,name,column in [('enter','fk_enter_user','user_id'),('enter','fk_enter_park','park_id'),('recharges','fk_recharges_user','user_id')]:
        exists=sql(f"select count(*) from information_schema.statistics where table_schema=database() and table_name='{table}' and index_name='{name}';").decode().strip().splitlines()[-1]
        if exists=='0':sql(f'alter table `{table}` add index `{name}` (`{column}`);')
    names={'orders':['idx_orders_user_time','idx_orders_park_time','idx_orders_time'],'enter':['idx_enter_user_time','idx_enter_park_time','idx_enter_time_flag'],'spaces':['idx_spaces_floor','idx_spaces_available'],'recharges':['idx_recharges_user_time','idx_recharges_flag_time','idx_recharges_time'],'user':['idx_user_created','idx_user_vip_expiry'],'historicalSession':['idx_session_user_type_time']}
    for table,indexes in names.items():
        for index in indexes:
            exists=sql(f"select count(*) from information_schema.statistics where table_schema=database() and table_name='{table}' and index_name='{index}';").decode().strip().splitlines()[-1]
            if exists!='0':sql(f'alter table `{table}` drop index `{index}`;')
    sql('alter table spaces drop index uk_space_coordinates, add unique index uk_space_coordinates(park_id,spaces_col,spaces_row,spaces_storey);')
    values=settings();values['BENCH_BACKEND_IMAGE']='parking-baseline-backend:797313c';values['BENCH_FRONTEND_IMAGE']='parking-baseline-frontend:797313c';values['BENCH_CACHE_ENABLED']='false';ENV.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
    compose('up','-d','--wait','backend','frontend');print('Original application and index set restored in isolated benchmark stack')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','seed','run','upgrade','baseline']);parser.add_argument('--label',choices=['baseline','optimized'],default='baseline');parser.add_argument('--clients',type=int,default=100);parser.add_argument('--warmup',type=int,default=120);parser.add_argument('--seconds',type=int,default=300);parser.add_argument('--rounds',type=int,default=3);parser.add_argument('--suffix',default='');args=parser.parse_args()
    if args.action=='prepare':prepare()
    elif args.action=='seed':seed()
    elif args.action=='upgrade':upgrade()
    elif args.action=='baseline':baseline()
    else:benchmark(args)
