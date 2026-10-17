"""Isolated covering-index read plans, allocated space, and 5000-row update costs."""
import json,statistics,uuid
import benchmark as bench
from importlib.machinery import SourceFileLoader
k6=SourceFileLoader('benchmark_k6',str(bench.ROOT/'scripts/benchmark-k6.py')).load_module()
database='parking_space_probe_'+uuid.uuid4().hex[:12]
assert database.startswith('parking_space_probe_') and len(database)==len('parking_space_probe_')+12
def sql(query):return k6.root_sql(query)
results={}
try:
    assert bench.sql("select count(*) from user where user_name like 'bench%';").decode().splitlines()[-1]=='100000'
    sql(f'CREATE DATABASE `{database}`;')
    for variant in ('baseline','covering'):
        table=f'`{database}`.`{variant}`'
        sql(f'CREATE TABLE {table} LIKE parking_springboot.spaces;')
        exists=sql(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='{database}' AND table_name='{variant}' AND index_name='idx_spaces_floor_state';").strip()
        if exists!='0':sql(f'ALTER TABLE {table} DROP INDEX idx_spaces_floor_state;')
        if variant=='covering':sql(f'CREATE INDEX idx_spaces_floor_state ON {table}(park_id,spaces_storey,id,spaces_flag);')
        sql(f'INSERT INTO {table} SELECT * FROM parking_springboot.spaces; ANALYZE TABLE {table};')
        size=int(sql(f"SELECT COALESCE(SUM(stat_value),0)*@@innodb_page_size FROM mysql.innodb_index_stats WHERE database_name='{database}' AND table_name='{variant}' AND stat_name='size' AND index_name<>'PRIMARY';").strip())
        plan=sql(f'EXPLAIN ANALYZE SELECT id,spaces_flag FROM {table} WHERE park_id=1 AND spaces_storey=1 AND spaces_flag IN(0,1) ORDER BY id;')
        results[variant]={'seed_rows':100000,'index_bytes':size,'plan':plan,'update_5000_ms':[]}
    for i in range(3):
        for variant in (('baseline','covering') if i%2==0 else ('covering','baseline')):
            elapsed=sql(f'SET @started=NOW(6); START TRANSACTION; UPDATE `{database}`.`{variant}` SET spaces_flag=1-spaces_flag WHERE id<=5000; COMMIT; SELECT TIMESTAMPDIFF(MICROSECOND,@started,NOW(6))/1000;')
            results[variant]['update_5000_ms'].append(float(elapsed.strip()))
    for value in results.values():value['median_ms']=statistics.median(value['update_5000_ms'])
    bench.WORK.joinpath('space-index-profile.json').write_text(json.dumps(results,indent=2))
    print(json.dumps(results))
finally:sql(f'DROP DATABASE IF EXISTS `{database}`;')
