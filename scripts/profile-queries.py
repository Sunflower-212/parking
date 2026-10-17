"""Capture synthetic-data query plans and dashboard SQL round trips in parking-benchmark only."""
import json,time
from pathlib import Path
import benchmark as bench

queries=[
    "EXPLAIN ANALYZE SELECT id FROM orders WHERE user_id=1 ORDER BY create_time DESC,id DESC LIMIT 20;",
    "EXPLAIN ANALYZE SELECT id FROM spaces WHERE park_id=1 AND spaces_flag=0 AND active_enter_id IS NULL ORDER BY id LIMIT 1;",
    "EXPLAIN ANALYZE SELECT DATE(create_time),SUM(moneys) FROM orders WHERE create_time>=CURRENT_DATE-INTERVAL 6 DAY AND create_time<CURRENT_DATE+INTERVAL 1 DAY GROUP BY DATE(create_time);",
]
bench.WORK.joinpath('explain-after.txt').write_bytes(bench.sql('\n'.join(queries)))
credentials=bench.settings()
auth=bench.login('benchmark',credentials['BENCH_ADMIN_PASSWORD'],True)
bench.compose('exec','-T','redis-cache','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB')
def snapshot():
    query="SELECT DIGEST,DIGEST_TEXT,COUNT_STAR FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='parking_springboot' AND DIGEST_TEXT LIKE 'SELECT %';"
    output=bench.compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names parking_springboot',input=query.encode())
    result={}
    for line in output.decode().splitlines():
        digest,statement,count=line.split('\t')
        if 'PERFORMANCE_SCHEMA' not in statement.upper() and 'VERSION_COMMENT' not in statement.upper() and statement!='SELECT ?':result[digest]=(statement,int(count))
    return result
before=snapshot();status,data,cold=bench.request('/dashboard/summary',auth)
if status!=200 or data.get('code')!=0:raise RuntimeError('Cold dashboard probe failed')
after=snapshot();statements=[{'sql':statement,'count':count-before.get(digest,('',0))[1]} for digest,(statement,count) in after.items() if count>before.get(digest,('',0))[1]]
warm=[bench.request('/dashboard/summary',auth)[2] for _ in range(20)]
result={'cold_ms':round(cold,2),'warm_p95_ms':bench.percentile(warm,.95),'cold_sql_count':sum(s['count'] for s in statements),'statements':statements}
bench.WORK.joinpath('query-profile.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
