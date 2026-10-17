"""Reproducible feature gates followed by all three full versions. Allow at least 70 minutes."""
import argparse,json,statistics,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.work';RESULTS=WORK/'k6'
def run(version,label,local=False,index=False,full=False,mode='mixed'):
    stage={'version':version,'label':label,'local':local,'index':index,'full':full,'mode':mode,'started':time.strftime('%Y-%m-%d %H:%M:%S')}
    RESULTS.mkdir(exist_ok=True);RESULTS.joinpath('stage.json').write_text(json.dumps(stage))
    print('STAGE '+json.dumps(stage),flush=True)
    args=[sys.executable,'-X','utf8','scripts/benchmark-k6.py','--version',version,'--label',label,'--mode',mode,
        '--warmup','120' if full else '10','--seconds','300' if full else '30','--rounds','3']
    if local:args.append('--local')
    if index:args.append('--index')
    subprocess.run(args,cwd=ROOT,check=True)
    return [json.loads(RESULTS.joinpath(f'{label}-{i}.json').read_text()) for i in (1,2,3)]
def median(rows,key):return statistics.median(row['groups'][key]['p(95)'] for row in rows)
def qualifies(before,after):
    gain=1-median(after,'floor')/median(before,'floor')
    regression=max(median(after,key)/median(before,key)-1 for key in ('reservation','cancellation'))
    return {'read_gain':gain,'write_regression':regression,'accepted':gain>=.1 and regression<=.05}
if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--full-only',action='store_true',help='Reuse completed feature gates and rerun all full versions on the current business date')
    args=parser.parse_args()
    if args.full_only:
        decision=json.loads(RESULTS.joinpath('decisions.json').read_text())
        local,index=decision['local'],decision['index']
    else:
        off=run('candidate','ab-local-off')
        on=run('candidate','ab-local-on',local=True)
        local=qualifies(off,on);chosen=on if local['accepted'] else off
        probe=json.loads(WORK.joinpath('space-index-profile.json').read_text())
        write_cost=probe['covering']['median_ms']/probe['baseline']['median_ms']-1
        index={'write_cost':write_cost,'accepted':False}
        if write_cost<=.05:
            indexed=run('candidate','ab-covering',local=local['accepted'],index=True)
            index.update(qualifies(chosen,indexed))
        else:index['reason']='Independent update-cost gate exceeded 5%; not enabled'
        decision={'measured_at':time.strftime('%Y-%m-%d %H:%M:%S %z'),'local':local,'index':index}
        RESULTS.joinpath('decisions.json').write_text(json.dumps(decision,indent=2))
    print('GATES '+json.dumps(decision),flush=True)
    for version in ('original','released','candidate'):
        run(version,version,local=version=='candidate' and local['accepted'],index=version=='candidate' and index['accepted'],full=True)
    RESULTS.joinpath('stage.json').write_text(json.dumps({'status':'full-matrix-complete','finished':time.strftime('%Y-%m-%d %H:%M:%S')}))
    print('PASS: all three versions completed three full rounds without unexpected errors',flush=True)
