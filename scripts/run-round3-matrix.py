"""Run the authorized isolated matrix serially. No deployment or publication is performed."""
import argparse,hashlib,json,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.work'
STAGE=WORK/'round3-full-stage.json'

def run(label,image,permits,buckets,rounds=3,warmup=120,seconds=300,profile='mixed'):
    STAGE.write_text(json.dumps({'stage':'running','label':label,'started_at':time.strftime('%Y-%m-%d %H:%M:%S'),'profile':profile}))
    print('START '+label,flush=True)
    subprocess.run([sys.executable,'-X','utf8','scripts/benchmark-round3.py','--image',image,'--label',label,'--permits',str(permits),'--buckets',str(buckets),'--rounds',str(rounds),'--warmup',str(warmup),'--seconds',str(seconds),'--profile',profile],cwd=ROOT,check=True)
    print('FINISHED '+label,flush=True)

def gate():
    rows=[json.loads((WORK/'k6-round3'/f'final-full-{n}.json').read_text()) for n in (1,2,3)]
    baseline=[json.loads((WORK/'k6-round3'/f'released-full-{n}.json').read_text()) for n in (1,2,3)]
    for row in rows:
        assert row['errors']['rate']<.001 and row.get('rejected',0)==0
        for name in ('orders','floor'):assert row['groups'][name]['p(95)']<=10,(name,row['round'])
        for name in ('reservation','cancellation'):assert row['groups'][name]['p(95)']<=500,(name,row['round'])
    assert sum(r['rps'] for r in rows)>=sum(r['rps'] for r in baseline)*1.1,'Throughput improvement below 10%'

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--skip-ab',action='store_true');parser.add_argument('--skip-full',action='store_true');parser.add_argument('--skip-extra',action='store_true');parser.add_argument('--resume-extra',action='store_true',help='Reuse completed extra profiles only after verifying their image, load hash and consistency');args=parser.parse_args()
    try:
        if not args.skip_ab:
            run('admission-only','parking-round3-backend:admission-only',8,1,warmup=30,seconds=60)
            run('final-single-counter','parking-round3-backend:final',8,1,warmup=30,seconds=60)
        if not args.skip_full:
            run('released-full','parking-released-backend:21d3012',4,1)
            run('current-full','parking-current-backend:1867459',4,1)
            run('final-full','parking-round3-backend:final',8,16)
        gate()
        if not args.skip_extra:
            for label,profile in (('final-multi','multi'),('final-contention','contention')):
                path=WORK/'k6-round3'/f'{label}-1.json'
                if args.resume_extra and path.exists():
                    row=json.loads(path.read_text());reference=json.loads((WORK/'k6-round3/final-full-1.json').read_text())
                    assert row['backend_image_id']==reference['backend_image_id'] and row['profile']==profile
                    assert row['clients']==100 and row['warmup']==120 and row['seconds']==300
                    assert row['errors']['count']==0 and row.get('rejected',0)==0 and row.get('consistency') and not any(row['consistency'].values())
                    assert row['experiment']['load_script_sha256']==hashlib.sha256((ROOT/'scripts/benchmark-k6.js').read_bytes()).hexdigest()
                    print('REUSE verified completed '+label,flush=True)
                else:run(label,'parking-round3-backend:final',8,16,rounds=1,profile=profile)
            run('final-arrival','parking-round3-backend:final',8,16,rounds=1,profile='arrival')
            run('final-soak','parking-round3-backend:final',8,16,rounds=1,profile='soak',seconds=3600)
            soak=json.loads((WORK/'k6-round3/final-soak-1.json').read_text())
            assert soak.get('expiry_probe')=={'held':20,'released':20},'Real expiry cohort did not fully release'
        STAGE.write_text(json.dumps({'stage':'complete','finished_at':time.strftime('%Y-%m-%d %H:%M:%S')}))
    except Exception as error:
        old=json.loads(STAGE.read_text()) if STAGE.exists() else {}
        STAGE.write_text(json.dumps({**old,'stage':'failed','error':str(error)}))
        raise
