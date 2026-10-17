"""Measure order-index storage and insert cost in disposable benchmark tables only."""
import json
import statistics
import uuid
import benchmark as bench

database = 'parking_index_probe_' + uuid.uuid4().hex[:12]
assert database.startswith('parking_index_probe_') and len(database) == len('parking_index_probe_') + 12

def sql(query):
    return bench.compose('exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names', input=query.encode()).decode().strip()

if sql("SELECT COUNT(*) FROM parking_springboot.user WHERE user_name LIKE 'bench%';") != '100000':
    raise RuntimeError('Expected isolated benchmark fixture')
results = {}
try:
    sql(f'CREATE DATABASE `{database}`;')
    for variant in ('baseline', 'optimized'):
        table = f'`{database}`.`{variant}`'
        sql(f'CREATE TABLE {table} LIKE parking_springboot.orders;')
        if variant == 'baseline':
            sql(f'ALTER TABLE {table} DROP INDEX idx_orders_user_time, DROP INDEX idx_orders_park_time, DROP INDEX idx_orders_time;')
        sql(f'INSERT INTO {table} SELECT * FROM parking_springboot.orders WHERE id<=1000000; ANALYZE TABLE {table};')
        index_bytes = int(sql(f"SELECT COALESCE(SUM(stat_value),0)*@@innodb_page_size FROM mysql.innodb_index_stats WHERE database_name='{database}' AND table_name='{variant}' AND stat_name='size' AND index_name<>'PRIMARY';"))
        results[variant] = {'seed_rows': 1000000, 'secondary_index_bytes': index_bytes, 'insert_5000_ms': []}
    columns = 'plate_num,user_id,park_id,enter_time,exit_time,parking_duration,moneys,create_time'
    # Alternate order to reduce systematic warm-up bias. Each statement commits the same fixture rows.
    for round_no in range(3):
        for variant in (('baseline','optimized') if round_no % 2 == 0 else ('optimized','baseline')):
            elapsed = sql(f'''SET @probe_start=NOW(6); START TRANSACTION;
INSERT INTO `{database}`.`{variant}` ({columns}) SELECT {columns} FROM parking_springboot.orders ORDER BY id LIMIT 5000;
COMMIT; SELECT TIMESTAMPDIFF(MICROSECOND,@probe_start,NOW(6))/1000;''')
            results[variant]['insert_5000_ms'].append(float(elapsed))
    for value in results.values():
        value['median_ms'] = statistics.median(value['insert_5000_ms'])
    bench.WORK.joinpath('index-write-profile.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results))
finally:
    sql(f'DROP DATABASE IF EXISTS `{database}`;')
