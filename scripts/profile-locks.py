"""Isolated, instrumented lock diagnostics; these short runs are not release benchmarks."""
import json,subprocess,sys,threading,time
from collections import Counter
from pathlib import Path
import benchmark as bench

ROOT=bench.ROOT


def query(source):
    return subprocess.check_output(['docker','exec','-i','parking-benchmark-mysql-1','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names parking_springboot'],input=source.encode(),cwd=ROOT).decode()


def counters():
    return {key:int(value) for line in query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits'); SELECT 'Innodb_deadlocks',COUNT FROM information_schema.innodb_metrics WHERE NAME='lock_deadlocks';").splitlines() for key,value in [line.split('\t')]}


def run(label,image,buckets,permits=8):
    before=counters();samples=[];stop=threading.Event()
    def sample():
        while not stop.is_set():
            values=query("SELECT l.OBJECT_NAME,COALESCE(l.INDEX_NAME,'table'),l.LOCK_MODE,COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='parking_springboot' GROUP BY l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE;")
            samples.append([dict(zip(('table','index','mode','waiting'),[cells[0],cells[1],cells[2],int(cells[3])])) for line in values.splitlines() if len(cells:=line.split('\t'))==4])
            stop.wait(1)
    worker=threading.Thread(target=sample,daemon=True);worker.start()
    try:
        subprocess.run([sys.executable,'-X','utf8','scripts/benchmark-round3.py','--image',image,'--label',label,'--permits',str(permits),'--buckets',str(buckets),'--rounds','1','--warmup','30','--seconds','60'],cwd=ROOT,check=True)
    finally:stop.set();worker.join(timeout=10)
    after=counters();counts=Counter()
    for sample in samples:
        for row in sample:counts[row['table']+'/'+row['index']+'/'+row['mode']]+=row['waiting']
    result={'label':label,'image':image,'buckets':buckets,'permits':permits,'condition':'One 30s warmup/60s sampled run plus setup, with a lock-table query about once per second. COUNT(*) counts blocking relationships, not distinct waiting transactions; repeated snapshots are not unique wait events.',
            'mysql_status_delta':{k:after[k]-before[k] for k in before},'snapshot_count':len(samples),'peak_blocking_relationships':max((sum(r['waiting'] for r in sample) for sample in samples),default=0),'blocking_relationship_snapshot_totals':dict(counts)}
    path=bench.WORK/'k6-round3'/f'{label}-1.json'
    data=json.loads(path.read_text());result['backend_image_id']=data['backend_image_id'];data['mysql_lock_diagnostics']=result;path.write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps(result),flush=True)
    return result


if __name__=='__main__':
    if subprocess.check_output(['docker','ps','-q','--filter','name=^/parking-benchmark-load$'],cwd=ROOT).strip():raise SystemExit('Refusing concurrent diagnostics')
    # MQ cost measurements must have completed before this optional service is stopped.
    if json.loads((bench.WORK/'round3-full-stage.json').read_text())['stage']!='complete':raise SystemExit('Wait for the complete core matrix')
    subprocess.run(['docker','stop','parking-benchmark-rabbitmq-1'],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
    result=[run('locks-current','parking-current-backend:1867459',1,4),run('locks-single','parking-round3-backend:final',1),run('locks-buckets','parking-round3-backend:final',16)]
    (bench.WORK/'lock-diagnostics.json').write_text(json.dumps(result,indent=2)+'\n')
