"""Measure export and broker costs only in the prepared synthetic benchmark project."""
import http.client
import json
import subprocess
import time
from pathlib import Path
import benchmark as bench


def docker(*args):
    return subprocess.check_output(['docker', *args], cwd=bench.ROOT).decode()


def measure():
    if docker('ps', '-q', '--filter', 'name=^/parking-benchmark-load$').strip():
        raise RuntimeError('Wait for the serial workload to finish before measuring the idle export')
    count=bench.sql("select count(*) from user where user_name like 'bench%'; select count(*) from user;").decode().strip().splitlines()
    if count[-1]!='100000' or count[1]!='100000':
        raise RuntimeError('Synthetic fixture identity mismatch')
    token=bench.login('benchmark',bench.settings()['BENCH_ADMIN_PASSWORD'],True)
    connection=http.client.HTTPConnection('127.0.0.1',18080,timeout=120)
    start=time.perf_counter()
    try:
        connection.request('GET','/user/export',headers={'Authorization':token})
        response=connection.getresponse()
        size=lines=0
        while data:=response.read(65536):
            size+=len(data);lines+=data.count(b'\n')
        duration=(time.perf_counter()-start)*1000
        if response.status!=200 or 'text/csv' not in response.getheader('Content-Type','') or lines!=100001:
            raise RuntimeError('Synchronous export failed or fixture row count changed')
    finally:
        connection.close()
    broker='parking-benchmark-rabbitmq-1'
    queues=docker('exec',broker,'rabbitmqctl','-q','list_queues','name','messages_ready','messages_unacknowledged','messages_persistent').strip()
    disk=docker('exec',broker,'du','-sk','/var/lib/rabbitmq').split()[0]
    connections=docker('exec',broker,'rabbitmqctl','-q','list_connections','user','channels').splitlines()
    channels=[int(line.split()[-1]) for line in connections if line.split() and line.split()[0]=='parking']
    resources=json.loads(docker('stats','--no-stream','--format','{{json .}}',broker))
    report={
        'condition':'Idle synchronous export after three background-enabled workload rounds; not a matched concurrent export comparison',
        'fixture_users':100000,'sync_export_ms':duration,'sync_csv_lines':lines,'sync_csv_bytes':size,
        'rabbitmq_data_kib':int(disk),'rabbitmq_queue_summary':queues,
        'rabbitmq_application_connections':len(channels),'rabbitmq_application_channels':sum(channels),
        'rabbitmq_idle_sample':{key:resources[key] for key in ('Name','CPUPerc','MemUsage','NetIO','BlockIO')},
    }
    path=bench.WORK/'background-cost.json'
    path.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report),flush=True)


if __name__=='__main__':measure()
