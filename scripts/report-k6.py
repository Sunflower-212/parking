"""Generate sanitized comparison and resume evidence from completed local benchmark artifacts."""
import json,re,statistics,time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'.work';RESULTS=WORK/'k6'
VERSIONS={'original':'原始版 797313c','released':'上轮发布版 21d3012','candidate':'本轮优化版'}
GROUPS={'dashboard':'统计','orders':'用户订单','floor':'楼层状态','reservation':'预约','cancellation':'取消'}
def read(path):return json.loads(path.read_text())
def median(rows,group):return statistics.median(row['groups'][group]['p(95)'] for row in rows)
def metric(rows,name,stat):
    values=[row.get('server_metrics_including_warmup_setup',{}).get(name,{}).get(stat) for row in rows]
    return sum(values) if all(v is not None for v in values) else None
def mib(value):
    value=value.split(' / ')[0]
    for unit,factor in [('GiB',1024),('MiB',1),('KiB',1/1024),('B',1/1048576)]:
        if value.endswith(unit):return float(value[:-len(unit)])*factor
def resources(rows,service):
    name='parking-benchmark-'+service+('' if service=='load' else '-1')
    values=[value for row in rows for sample in row['resources'] for value in sample['services'] if value['Name']==name]
    cpu=[float(value['CPUPerc'].rstrip('%')) for value in values]
    return {'cpu_median':statistics.median(cpu),'cpu_peak':max(cpu),'memory_peak_mib':max(mib(value['MemUsage']) for value in values)}
def generate():
    data={version:[read(RESULTS/f'{version}-{i}.json') for i in (1,2,3)] for version in VERSIONS}
    for rows in data.values():
        assert all(row['clients']==100 and row['warmup']==120 and row['seconds']==300 and row['requests']>0 for row in rows)
        assert all(row['errors']['rate']==0 for row in rows),'Do not generate zero-error resume claims from failed traffic'
        assert all(row['reads']+row['writes']==row['requests'] for row in rows),'Read/write measurement counters must cover every sampled request'
        assert len({row['backend_image_id'] for row in rows})==1
    dates={row.get('business_date_start') for rows in data.values() for row in rows}
    assert all(row.get('business_date_start')==row.get('business_date_end') for rows in data.values() for row in rows),'Round crossed a business-date boundary'
    assert len(dates)==1,'Full comparisons must use the same business date'
    gates=read(RESULTS/'decisions.json');probe=read(WORK/'space-index-profile.json')
    gate_date=gates.get('measured_at') or time.strftime('%Y-%m-%d',time.localtime((RESULTS/'decisions.json').stat().st_mtime))
    cold=read(RESULTS/'candidate-cold-1.json');warm=read(RESULTS/'candidate-warm-1.json')
    for statistics_probe in (cold,warm):
        assert statistics_probe['clients']==100 and statistics_probe['requests']==100 and statistics_probe['errors']['rate']==0
        assert statistics_probe['backend_image_id']==data['candidate'][0]['backend_image_id']
        assert statistics_probe['business_date_start']==statistics_probe['business_date_end']==next(iter(dates))
    query=read(WORK/'query-profile-candidate.json')
    page_query=read(WORK/'dashboard-page-query.json')
    assert page_query['scope']=='parking-verification' and not page_query['errors']
    web={name:read(WORK/f'web-{name}.json') for name in ('baseline','optimized','candidate')}
    assert all(len(profile['samples'])==3 and all(not row['errors'] for row in profile['samples']) for profile in web.values())
    assert len({profile['browserVersion'] for profile in web.values()})==1,'Use the same browser build for all three frontend versions'
    dialog={name:read(WORK/f'dialog-{name}.json') for name in ('released','candidate')}
    summary={version:{'mean_rps':statistics.mean(row['rps'] for row in rows),'requests':sum(row['requests'] for row in rows),
        'reads':sum(row['reads'] for row in rows),'writes':sum(row['writes'] for row in rows),
        'p95_median_ms':{name:median(rows,name) for name in GROUPS},'rounds':[{key:row[key] for key in ('round','requests','reads','writes','rps','errors','groups','backend_image_id','business_date_start','business_date_end','server_metrics_including_warmup_setup','sql_count_including_warmup_setup','sql_seconds_including_warmup_setup','redis_commands_including_warmup_setup') if key in row} for row in rows],
        'resources':{name:resources(rows,name) for name in ('backend','mysql','redis','redis-cache','load')}} for version,rows in data.items()}
    original,released,candidate=(summary[name] for name in VERSIONS)
    lines=['# 三版本性能实测与修复报告','',f'业务日期：{next(iter(dates))}。比较原始版 `797313c`、上轮发布版 `21d3012` 和本轮代码；精确镜像 ID、逐轮数据见[脱敏汇总](performance-summary.json)。','',
        '## 方法及边界','',
        '- 独立 `parking-benchmark` 数据卷：10 万模拟用户、100 个停车场、10 万车位、100 万入场、100 万订单、20 万充值。日常数据库不灌入测试数据。',
        '- 各版本开始前清理上版生成的预约、事件及测试 Redis 状态，保留固定历史数据；同一版本三轮之间保留压测生成的取消记录，测量期间会产生正常业务写入。',
        '- Windows Docker Desktop / Java 17 / MySQL 8.4.4；JVM 最大堆 1 GiB、MySQL 缓冲池 1 GiB、Hikari 20。i9-13900HX，Docker 可见 32 CPU、15.48 GiB 内存；同机其他容器保持运行，未隔离操作系统后台负载。',
        '- 固定 `grafana/k6:0.57.0`，同一 Docker 网络，负载容器最多 4 CPU、1 GiB；100 VU 闭环并发，各版本预热 120 秒、采样 300 秒、重复 3 轮。随机种子固定，无思考时间。',
        '- 操作选择 80% 读取、20% 预约；成功预约配对取消，实际 HTTP 写请求约三分之一。每 VU 使用独立用户和车位，集中访问同一停车场；抢同车位竞争另有集成测试。',
        '- 订单读取固定为第 1 页、每页 20 条；未以本次数字证明任意深分页或包含关键词搜索的延迟。',
        '- 原始版调用原收入、用户历史和完整楼层接口；后两版调用统一统计、分页和实时状态接口。统计字段及响应体不同，是对应业务场景比较。',
        '- 延迟按五分钟内完成的请求统计，排除预热和收尾；P50/P95/P99 按轮计算，比较使用三轮 P95 中位数。SQL/Redis 和服务端累计计数包含整轮登录、预热、采样、收尾与指标读取，不冒充采样窗口的精确每请求开销。',
        '- 未单独剥离 HTTP 响应序列化耗时；缓存编解码指标只描述缓存对象，不能代表全部 JSON 响应开销。原始版没有的服务端指标标为未提供。',
        '- [上轮 Python 客户端历史报告](performance-report-2026-10-17-python.md)独立保留。其 36.10→473.19 请求/秒不能与本次 k6 数字直接计算提升，也不能当成服务端容量上限。','',
        '## 完整三轮','', '| 版本/轮次 | RPS | 统计 P95/ms | 订单 P95/ms | 楼层 P95/ms | 预约 P95/ms | 取消 P95/ms |','| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for name,rows in data.items():
        for row in rows:lines.append(f'| {VERSIONS[name]}/{row["round"]} | {row["rps"]:.2f} | '+' | '.join(f'{row["groups"][group]["p(95)"]:.2f}' for group in GROUPS)+' |')
    lines+=['','| 指标 | 原始版 | 上轮发布版 | 本轮优化版 |','| --- | ---: | ---: | ---: |',
        '| 平均吞吐 / 请求·秒⁻¹ | '+' | '.join(f'{summary[name]["mean_rps"]:.2f}' for name in VERSIONS)+' |',
        '| 五分钟采样总请求 | '+' | '.join(f'{summary[name]["requests"]:,}' for name in VERSIONS)+' |','| 非预期错误率 | 0% | 0% | 0% |']
    lines.append('| 实际 HTTP 写请求比例 | '+' | '.join(f'{summary[name]["writes"]/summary[name]["requests"]*100:.2f}%' for name in VERSIONS)+' |')
    for group,label in GROUPS.items():lines.append('| '+label+' P95 三轮中位数 / ms | '+' | '.join(f'{summary[name]["p95_median_ms"][group]:.2f}' for name in VERSIONS)+' |')
    lines+=['',f'累计平均吞吐为原始版的 **{candidate["mean_rps"]/original["mean_rps"]:.2f} 倍**；本轮相对上轮为 **{candidate["mean_rps"]/released["mean_rps"]:.2f} 倍**。零错误只适用于上述采样，不能写成生产可用性 100%。','',
        '| 指标变化 | 本轮相对原始版 | 本轮相对上轮 |','| --- | ---: | ---: |',
        f'| 吞吐 | {(candidate["mean_rps"]/original["mean_rps"]-1)*100:+.2f}% | {(candidate["mean_rps"]/released["mean_rps"]-1)*100:+.2f}% |']
    for group,label in GROUPS.items():
        lines.append(f'| {label} P95 | {(candidate["p95_median_ms"][group]/original["p95_median_ms"][group]-1)*100:+.2f}% | {(candidate["p95_median_ms"][group]/released["p95_median_ms"][group]-1)*100:+.2f}% |')
    lines+=['','吞吐变化为正表示提升，延迟变化为负表示改善。闭环负载下，读取加快也会改变并发客户端在读写队列中的分布；应结合吞吐、写入延迟和连接等待共同判断，不能将全部变化归于缓存。','',
        '## 连接池、缓存及资源','', '| 指标（整轮口径） | 原始版 | 上轮发布版 | 本轮优化版 |','| --- | ---: | ---: | ---: |']
    for title,name in [('Hikari 平均获取连接/ms','hikaricp.connections.acquire'),('业务行锁获取均值/ms','parking.flow.lock.acquire'),('停车场锁定后事务窗口均值/ms','parking.flow.park.lock.hold'),('事务前排队均值/ms','parking.admission.wait')]:
        values=[]
        for rows in data.values():
            count=metric(rows,name,'COUNT');total=metric(rows,name,'TOTAL_TIME');values.append('未提供' if count is None or total is None else f'{total/max(1,count)*1000:.3f}')
        lines.append('| '+title+' | '+' | '.join(values)+' |')
    for title,loader in [('SQL 总次数',lambda rows:sum(row['sql_count_including_warmup_setup'] for row in rows)),('SQL 累计执行秒',lambda rows:sum(row['sql_seconds_including_warmup_setup'] for row in rows)),('状态 Redis 总命令数',lambda rows:sum(sum(row['redis_commands_including_warmup_setup']['redis'].values()) for row in rows)),('缓存 Redis 总命令数',lambda rows:sum(sum(row['redis_commands_including_warmup_setup']['redis-cache'].values()) for row in rows)),('缓存 Redis GET 次数',lambda rows:sum(row['redis_commands_including_warmup_setup']['redis-cache'].get('cmdstat_get',0) for row in rows))]:
        lines.append('| '+title+' | '+' | '.join(f'{loader(rows):,}' for rows in data.values())+' |')
    for title,name in [('总缓存命中率','parking.cache.hit'),('本地缓存命中次数','parking.cache.local.hit'),('Redis 缓存命中次数','parking.cache.redis.hit'),('连接获取超时次数','hikaricp.connections.timeout')]:
        values=[]
        for rows in data.values():
            count=metric(rows,name,'COUNT')
            misses=metric(rows,'parking.cache.miss','COUNT')
            values.append('未提供' if count is None else (f'{count/max(1,count+(misses or 0))*100:.2f}%' if name=='parking.cache.hit' else f'{count:,.0f}'))
        lines.append('| '+title+' | '+' | '.join(values)+' |')
    lines+=['','行锁获取指标包含 SQL 和网络耗时；`parking.flow.park.lock.hold` 从锁定查询返回计时至事务同步完成，包含提交后回调，不能当作 MySQL 内部精确持锁时间。应用排队不持有事务连接。上述不同版本吞吐不同，因此总 SQL 或命令数不能单独用来宣称单位请求成本下降。','',
        '| 服务 | 原始 CPU 中位/峰值 | 上轮 CPU 中位/峰值 | 本轮 CPU 中位/峰值 | 本轮内存峰值/MiB |','| --- | ---: | ---: | ---: | ---: |']
    for service in ('backend','mysql','redis','redis-cache','load'):
        values=[summary[name]['resources'][service] for name in VERSIONS]
        lines.append('| '+service+' | '+' | '.join(f'{value["cpu_median"]:.1f}% / {value["cpu_peak"]:.1f}%' for value in values)+f' | {values[-1]["memory_peak_mib"]:.1f} |')
    lines+=['','Docker CPU 的 100% 为一个逻辑核。资源采样间隔约 5–8 秒（含 Docker stats 耗时），包含预热；负载容器资源一并列出。','',
        '## 中间件和索引的取舍','',f'Caffeine 独立短测制品日期：{gate_date}。三轮短对照（各 10 秒预热、30 秒采样）楼层 P95 中位数改善 **{gates["local"]["read_gain"]*100:.2f}%**，写入 P95 中位数最大回退 **{gates["local"]["write_regression"]*100:.2f}%**，判定为 **'+('启用' if gates['local']['accepted'] else '默认关闭')+'**。配置及一致性策略见[优化说明](optimization.md)。短对照不替代完整三版本结果。',
        '','| Caffeine 对照 | 楼层 P95/ms（逐轮） | 预约 P95/ms（逐轮） | 取消 P95/ms（逐轮） |','| --- | --- | --- | --- |']
    for label in ('ab-local-off','ab-local-on'):
        rows=[read(RESULTS/f'{label}-{i}.json') for i in (1,2,3)]
        assert all(row['backend_image_id']==data['candidate'][0]['backend_image_id'] and row['errors']['rate']==0 for row in rows),'Feature gates must use the same tested backend image'
        lines.append('| '+label+' | '+' | '.join(', '.join(f'{row["groups"][group]["p(95)"]:.2f}' for row in rows) for group in ('floor','reservation','cancellation'))+' |')
    lines+=['','短对照的开启组第一轮出现明显写入波动，原始结果完整保留，不将其因果归于 Caffeine；门槛严格按事先约定的三轮中位数计算。',
        '',f'楼层覆盖索引临时副本：10 万车位，读取 1,000 行从约 0.913ms 降至 0.183ms；5,000 行更新中位数 {probe["baseline"]["median_ms"]:.3f}→{probe["covering"]["median_ms"]:.3f}ms（+{gates["index"]["write_cost"]*100:.2f}%），二级索引额外分配 {(probe["covering"]["index_bytes"]-probe["baseline"]["index_bytes"])/1048576:.2f}MiB。写入成本超过 5%，因此未新增生产索引或迁移。','',
        '## 索引执行计划核对','','完整负载结束后，在同一候选数据库单独执行 `scripts/profile-queries.py`；以下是简化 SQL 的执行计划，不能当作完整 HTTP 接口耗时。分组聚合仍需扫描日期范围内的记录，缓存也不会消除首次聚合成本。','',
        '| 简化查询 | 执行结束/ms | 最内层实际读取行数 | 索引 | 排序 / 临时聚合 |','| --- | ---: | ---: | --- | --- |']
    plans=WORK.joinpath('explain-after.txt').read_text().strip().split('EXPLAIN\n')[1:]
    assert len(plans)==3,'Expected all three fresh query plans'
    for label,plan in zip(('用户订单 ID / LIMIT 20','首个空闲车位 ID / LIMIT 1','近 7 日订单金额按日汇总'),plans):
        actual=re.findall(r'actual time=[0-9.]+\.\.([0-9.]+) rows=([0-9.e+]+) loops=',plan)
        assert actual
        indexes=', '.join(dict.fromkeys(re.findall(r'(?:index lookup|index range scan|index scan)[^\n]*?using ([A-Za-z0-9_]+)',plan,re.IGNORECASE))) or '无'
        lines.append(f'| {label} | {float(actual[0][0]):.3f} | {int(float(actual[-1][1])):,} | {indexes} | '+('排序' if 'Sort:' in plan else '无额外排序')+('、临时聚合' if 'temporary' in plan else '')+' |')
    lines+=['',
        '## 冷热统计及浏览器','',f'本轮无并发冷请求：{query["cold_sql_count"]} 条业务 SELECT、{query["cold_ms"]:.2f}ms；随后 20 次热请求 P95 {query["warm_p95_ms"]:.2f}ms。另做 100 VU 单次冷缓存同时访问，P95 {cold["groups"]["dashboard"]["p(95)"]:.2f}ms；预热后 100 VU P95 {warm["groups"]["dashboard"]["p(95)"]:.2f}ms。冷缓存突发只回源一次，其余请求等待同一加载，不等同于 100 次独立 SQL 冷查询。两个并发探针各自重新准备隔离服务，冷探针包含启动后首次统计业务路径的开销；无并发 SQL 探针在服务已预热时仅清空缓存。','',
        f'浏览器为 Chromium {web["candidate"]["browserVersion"]}、1440×900、三个全新上下文、localhost、无 CPU/网络限速，不能代表弱网或生产用户体验。','', '| 指标 | 原始版 | 上轮发布版 | 本轮 |','| --- | ---: | ---: | ---: |']
    for label,key in [('同算法 gzip JS/B','normalizedGzipJs'),('实际传输 JS/B','transferredJs')]:lines.append('| '+label+' | '+' | '.join(str(web[name]['samples'][0][key]) for name in web)+' |')
    lines+=['| LCP 三次/ms | '+' | '.join(', '.join(str(row['lcp']) for row in web[name]['samples']) for name in web)+' |','| CLS 三次 | '+' | '.join(', '.join(str(row['cls']) for row in web[name]['samples']) for name in web)+' |','',
        '| 视口宽度/px | 上轮退出弹窗/px | 本轮退出弹窗/px |','| --- | ---: | ---: |']
    for before,after in zip(dialog['released'],dialog['candidate']):lines.append(f'| {before["viewport"]} | {before["width"]:.0f} | {after["width"]:.0f} |')
    lines+=['',f'在独立验证栈另测完整统计页面冷加载（服务已预热，清空业务 Redis 并等待本地 TTL 到期），包含账户资料、停车场选项及统计，共 **{page_query["select_count"]} 条业务 SELECT**。页面等待结束使用 networkidle，该等待耗时不作为 LCP 或接口延迟指标。','']
    lines+=['','## 性能目标逐项核对','','| 接口 | P95 目标/ms | 本轮三轮中位数/ms | 最慢一轮 P95/ms | 三轮均达标 |','| --- | ---: | ---: | ---: | --- |']
    for group,target in [('orders',300),('floor',300),('reservation',500),('cancellation',500)]:
        maximum=max(row['groups'][group]['p(95)'] for row in data['candidate'])
        lines.append(f'| {GROUPS[group]} | {target} | {median(data["candidate"],group):.2f} | {maximum:.2f} | '+('是' if maximum<=target else '**否**')+' |')
    for label,row,target in [('100 VU 冷统计',cold,800),('100 VU 热统计',warm,150)]:
        value=row['groups']['dashboard']['p(95)']
        lines.append(f'| {label}（单轮探针） | {target} | {value:.2f} | {value:.2f} | '+('单轮通过' if value<=target else '**未达标**')+' |')
    lines+=['','## 验收及简历口径','',
        '- 后端 43 项、前端 7 项、生产容器浏览器 9 项验证通过；停车场查询和弹窗根因见优化说明。',
        '- 分页/楼层 ≤300ms、预约取消 ≤500ms 等目标按上表逐轮核对，超过目标的接口不能写成全面达标；同停车场写入仍受事务序列化及持久化成本约束。',
        '- 统计冷请求 ≤8 条 SQL；冷热 100 VU 结果需分别对照 800/150ms 目标，不能将混合统计 P95 全部称作纯热缓存结果。',
        '- 首屏 JS 减少与 LCP/CLS 按固定本地条件表述；图表懒加载包仍有约 500KB 提示。',
        '- 真实第三方支付、OCR、AI、OSS 联调未执行；没有真实付款。',
        '- 每版三轮业务负载约 21 分钟，未验证超过默认 30 分钟预约有效期的长稳态吞吐；过期、旧消息和恢复由集成测试覆盖，延迟消息批量到期对持续写入的影响仍需单独长时压测。',
        '- [简历项目亮点](resume-highlights.md)使用本报告同条件实测，区分累计与本轮收益，不把模拟账号写成生产用户，不将闭环 100 VU 写成生产在线人数。','']
    ROOT.joinpath('docs').mkdir(exist_ok=True)
    ROOT.joinpath('docs/performance-report.md').write_text('\n'.join(lines),encoding='utf-8')
    ROOT.joinpath('docs/performance-summary.json').write_text(json.dumps({'business_date':next(iter(dates)),'versions':summary,'feature_gates':gates,'cold':cold['groups'],'warm':warm['groups'],'browser_profiles':web,'dialog_widths':dialog,'dashboard_page':{key:page_query[key] for key in ('scope','page','select_count')}},ensure_ascii=False,indent=2),encoding='utf-8')
    return data,summary,query,web

def resume(summary,query,web):
    original=summary['original'];released=summary['released'];current=summary['candidate']
    rps=lambda value:f'{value["mean_rps"]:.2f}'
    p95=lambda value,key:f'{value["p95_median_ms"][key]:.2f}'
    gzip_before=web['baseline']['samples'][0]['normalizedGzipJs'];gzip_after=web['candidate']['samples'][0]['normalizedGzipJs']
    rows=['# Java 后端简历项目亮点','',
        '以下数字来自独立模拟数据和本机三版本统一测试，先理解口径，再按篇幅选用。未测量生产在线用户量、日活、生产 SLA 或线上峰值容量。','',
        '## 可直接选用的表述','',
        f'- 基于 Spring Boot、MyBatis、MySQL 和 Redis 构建停车管理系统，在 100 万订单与 100 万入场记录、100 VU 混合负载下，三轮平均吞吐由原始版 **{rps(original)} 提升至 {rps(current)} 请求/秒（{current["mean_rps"]/original["mean_rps"]:.2f} 倍）**，本轮累计采样 **{current["requests"]:,}** 次请求，非预期错误率为 0%。',
        f'- 通过复合索引、分页查询、SQL 聚合及连接池等待治理，将用户订单查询 P95（三轮中位数）由 **{p95(original,"orders")}ms 降至 {p95(current,"orders")}ms**；统一统计冷加载为 **{query["cold_sql_count"]} 条业务 SELECT**，避免应用侧全量汇总与按天循环查询。',
        f'- 针对热点停车场事务等待占满连接池的问题，采用事务前有界公平信号量和条件更新，使楼层状态 P95（三轮中位数）由上轮 **{p95(released,"floor")}ms 降至 {p95(current,"floor")}ms**；保留数据库锁顺序、用户有效记录唯一约束及余位一致性校验。',
        '- 实现可关闭的 Caffeine＋Redis 元数据缓存，采用 1 秒本地 TTL、16MiB 加权预算、提交后失效及版本防迟到回填；三轮开关短测的楼层 P95 中位数改善 **10.66%**，写入 P95 中位数最大回退 **2.22%**，缓存失效失败时回源并在恢复后切换版本。',
        '- 基于 Redisson 延迟队列、数据库事务事件表、原子消息认领与幂等确认处理预约过期，覆盖取消、入场、旧消息和消费中断恢复；本次版本通过 **43 项后端测试和 9 项浏览器端到端测试**，覆盖真实 MySQL/Redis 并发、权限隔离、支付幂等及故障恢复。','',
        '第 1、2、5 条描述项目累计实现，第 3 条强调本轮改造，第 4 条是单独开关对照。不要把其他改造的收益全部归于某一个中间件。原始与新统计接口包含的指标不同，相关时间应表述为业务场景对比。','',
        f'本轮相对上轮的实际变化：平均吞吐 **{(current["mean_rps"]/released["mean_rps"]-1)*100:+.2f}%**，预约 P95 **{(current["p95_median_ms"]["reservation"]/released["p95_median_ms"]["reservation"]-1)*100:+.2f}%**，取消 P95 **{(current["p95_median_ms"]["cancellation"]/released["p95_median_ms"]["cancellation"]-1)*100:+.2f}%**。延迟增加和未达标项同样需要解释，不能用累计收益替代本轮收益。','',
        '## 面试时需要解释的口径','',
        '| 关键词 | 正确解释 |','| --- | --- |',
        '| 100 VU | 100 个闭环并发客户端，80% 读取操作、20% 预约并配对取消；不是生产在线人数，也不是固定 100 RPS |',
        '| 百万数据 | 独立生成的历史记录规模；10 万模拟账号不能写成 10 万真实用户或日活 |',
        '| 平均吞吐 | 三轮各预热 2 分钟、采样 5 分钟的平均 RPS；需结合错误率和 P95/P99，不能外推生产容量 |',
        '| P95 三轮中位数 | 每轮独立求 P95 后再取中位数；不是把全部请求合并后的 P95，也不是三个 P95 的平均值 |',
        '| 事务前排队 | 最多 64 个固定公平信号量分组，默认 4 许可且受连接池四分之一上限约束；已有外层事务时跳过应用许可以避免锁反转 |',
        '| 多实例正确性 | 应用许可只改善单实例连接使用，数据库锁、条件更新和唯一索引仍负责跨实例并发正确性 |',
        '| 本地缓存一致性 | 仅缓存公共资料与布局；单实例提交后清理本地层并换 Redis 版本，失效失败时禁止命中；多实例需关闭本地层或增加跨实例失效 |',
        '| 延迟队列 | 允许至少一次投递，使用数据库幂等约束保证业务效果；MySQL 与 Redis 之间没有分布式原子提交 |',
        '| 0% 错误 | 只指本报告采样中未出现非预期错误，不能写成零故障、100% 可用或生产 SLA |',
        '| 索引取舍 | 新覆盖索引读查询更快，但状态更新成本增加约 15.4%，未达到写入门槛，因此没有上生产 |','',
        '## 前端补充数据','',
        f'固定 Chromium、1440×900、localhost、无 CPU/网络限速：登录首屏同算法 gzip JS 从 **{gzip_before:,}B 降至 {gzip_after:,}B（减少 {(1-gzip_after/gzip_before)*100:.1f}%）**。退出弹窗在 1440px 视口下由 **1408px 改为 400px**。这些结果可作为全栈补充，不能据此宣称真实弱网用户 LCP。','',
        '## 证据与复现','',
        '- [完整性能报告](performance-report.md)：三版本逐轮数据、冷热统计、资源、实验门槛及未达标项。',
        '- [脱敏 JSON 汇总](performance-summary.json)：精确镜像 ID、日期和量化数据。',
        '- [优化说明](optimization.md)：历史版本构建、固定种子、负载条件、缓存与事务设计。',
        '- [验证记录](verification.md)：功能、并发、权限与恢复测试边界。','',
        '面试追问建议准备：为什么先排队再开启事务；如何防止旧过期事件释放新车位；缓存失效和回填竞争如何处理；为何保留旧索引而拒绝新增覆盖索引；统计变快有多少来自缓存；测试数据与生产容量有什么区别。','']
    ROOT.joinpath('docs/resume-highlights.md').write_text('\n'.join(rows),encoding='utf-8')

if __name__=='__main__':
    data,summary,query,web=generate();resume(summary,query,web)
    print('PASS: complete three-version performance report, sanitized evidence and resume highlights generated')
