"""Serial optional-broker cost checks after the complete isolated core matrix."""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.work'

if __name__ == '__main__':
    if json.loads((WORK / 'round3-full-stage.json').read_text())['stage'] != 'complete':
        raise SystemExit('Complete the isolated core matrix first')
    if subprocess.check_output(['docker', 'ps', '-q', '--filter', 'name=^/parking-benchmark-load$'], cwd=ROOT).strip():
        raise SystemExit('Another load is running; do not overlap experiments')
    marker = WORK / 'round3-background-stage.json'
    if marker.exists() or list((WORK / 'k6-round3').glob('mq-enabled-*.json')):
        raise SystemExit('Prior evidence exists; inspect it before resuming. Results are never overwritten.')
    marker.write_text(json.dumps({'stage': 'running', 'started_at': time.strftime('%Y-%m-%d %H:%M:%S')}))
    try:
        subprocess.run([sys.executable, '-X', 'utf8', 'scripts/benchmark-round3.py',
                        '--image', 'parking-round3-backend:final', '--label', 'mq-enabled',
                        '--permits', '8', '--buckets', '16', '--profile', 'background', '--background',
                        '--rounds', '3', '--warmup', '120', '--seconds', '300'], cwd=ROOT, check=True)
        subprocess.run([sys.executable, '-X', 'utf8', 'scripts/measure-background.py'], cwd=ROOT, check=True)
        marker.write_text(json.dumps({'stage': 'complete', 'finished_at': time.strftime('%Y-%m-%d %H:%M:%S')}))
    except Exception as error:
        marker.write_text(json.dumps({'stage': 'failed', 'error': str(error)}))
        raise
    subprocess.run([sys.executable, '-X', 'utf8', 'scripts/profile-locks.py'], cwd=ROOT, check=True)
