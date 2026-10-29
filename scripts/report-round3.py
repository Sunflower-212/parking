"""Publish only complete, sanitized observations; never replace historical reports."""
import json,statistics,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
RAW=ROOT/'.work/k6-round3'
LABELS={'released-full':'上轮21d3012','current-full':'当前1867459','final-full':'容量与事务优化候选'}
GROUPS={'dashboard':'统计','orders':'用户订单','floor':'楼层状态','reservation':'预约','cancellation':'取消'}

def read(label,n):
    row=json.loads((RAW/f'{label}-{n}.json').read_text())
    assert row['requests']>0 and row['clients']==100 and row['business_date_start']==row['business_date_end']
    return row

# 这里计算三轮各自 P95 的中位数，不是合并全部请求后的总体 P95，也不能叫平均 P95。
def median(rows,name):return statistics.median(r['groups'][name]['p(95)'] for r in rows)
def mean_rps(rows):return statistics.mean(r['rps'] for r in rows)
def change(before,after):return (after/before-1)*100
def resource_summary(rows):
    samples={}
    for row in rows:
        for batch in row.get('resources',[]):
            for item in batch.get('services',[]):
                name=item.get('Name','').removeprefix('parking-benchmark-').removesuffix('-1')
                if name not in ('backend','mysql','redis','redis-cache','load','rabbitmq'):continue
                values=samples.setdefault(name,{'cpu':[],'memory':[]})
                try:values['cpu'].append(float(item['CPUPerc'].rstrip('%')))
                except (KeyError,ValueError):pass
                match=re.match(r'([0-9.]+)([A-Za-z]+)',item.get('MemUsage',''))
                if match:
                    scale={'B':1,'kB':1000,'KB':1000,'MB':1000**2,'GB':1000**3,'KiB':1024,'MiB':1024**2,'GiB':1024**3}.get(match[2])
                    if scale:values['memory'].append(float(match[1])*scale/1024**2)
    return {name:{'cpu_median_percent':statistics.median(v['cpu']) if v['cpu'] else None,'cpu_peak_percent':max(v['cpu']) if v['cpu'] else None,'memory_peak_mib':max(v['memory']) if v['memory'] else None,'samples':len(v['cpu'])} for name,v in samples.items()}
def timer_mean(rows,name):
    values=[r.get('server_metrics_including_warmup_setup',{}).get(name,{}) for r in rows]
    count=sum(v.get('COUNT',0) for v in values)
    return 1000*sum(v.get('TOTAL_TIME',0) for v in values)/count if count else None
def sanitize(row):
    allowed=['version','mode','profile','round','clients','warmup','seconds','requests','reads','writes','rps','errors','groups','conflicts','rejected','successfulRequests','committedReservations','committedCancellations','droppedIterations','backend_image_id','business_date_start','business_date_end','experiment','expiry_probe','server_metrics_including_warmup_setup','sql_count_including_warmup_setup','sql_seconds_including_warmup_setup','redis_commands_including_warmup_setup']
    result={key:row[key] for key in allowed if key in row}
    # k6's false Rate samples include expected conflicts/rejections, not just successes.
    result['errors']={'count':row['errors']['count'],'rate':row['errors']['rate'],'non_unexpected_count':row['errors'].get('successful',0)}
    for key in ('later_expiry_probe','expiry_sql_probe','consistency'):
        if key in row:result[key]=row[key]
    if 'collection_recovery' in row:
        result['collection_recovery']=row['collection_recovery']
    if 'consistency' in row:result['consistency_check_scope']='after all rounds of this label, shared final snapshot'
    for key in ('background_export_submission','background_export_result','mysql_lock_diagnostics'):
        if key in row:result[key]=row[key]
    result['resource_summary_including_warmup_setup']=resource_summary([row])
    return result

if __name__=='__main__':
    assert json.loads((ROOT/'.work/round3-full-stage.json').read_text())['stage']=='complete','Matrix incomplete: do not publish partial results'
    assert json.loads((ROOT/'.work/round3-background-stage.json').read_text())['stage']=='complete','Background cost checks incomplete'
    matrix={label:[read(label,n) for n in (1,2,3)] for label in LABELS}
    extra={label:[read(label,1)] for label in ('final-multi','final-contention','final-arrival','final-soak')}
    background=[read('mq-enabled',n) for n in (1,2,3)]
    background_cost=json.loads((ROOT/'.work/background-cost.json').read_text())
    lock_diagnostics=json.loads((ROOT/'.work/lock-diagnostics.json').read_text())
    for row in lock_diagnostics:
        if 'peak_simultaneous_waiters' in row:
            row['peak_blocking_relationships']=row.pop('peak_simultaneous_waiters')
            row['blocking_relationship_snapshot_totals']=row.pop('waiter_snapshot_totals')
        row['condition']='One 30s warmup/60s sampled run plus setup; lock snapshots count blocking relationships, not distinct waiting transactions or unique wait events.'
    for rows in matrix.values():
        assert len({r['backend_image_id'] for r in rows})==1
        assert all(r['warmup']==120 and r['seconds']==300 for r in rows)
        assert len({r['experiment']['load_script_sha256'] for r in rows})==1
    assert len({r['experiment']['load_script_sha256'] for rows in matrix.values() for r in rows})==1
    assert len({r['business_date_start'] for rows in matrix.values() for r in rows})==1,'Main comparison crossed business dates'
    final=matrix['final-full'];reference=matrix['released-full'];current=matrix['current-full']
    assert len({r['backend_image_id'] for r in final+background})==1,'Background and core runs used different candidate images'
    gates={
        'throughput_gain_10_percent':mean_rps(final)>=mean_rps(reference)*1.1,
        'every_round_reads_10_ms':all(r['groups'][g]['p(95)']<=10 for r in final for g in ('orders','floor')),
        'every_round_writes_500_ms':all(r['groups'][g]['p(95)']<=500 for r in final for g in ('reservation','cancellation')),
        'unexpected_errors_below_0_1_percent':all(r['errors']['rate']<.001 for r in final),
        'no_rejection_in_closed_loop':all(r.get('rejected',0)==0 for r in final),
        'soak_60_minutes_and_real_expiry':extra['final-soak'][0]['seconds']>=3600 and extra['final-soak'][0].get('expiry_probe')=={'held':20,'released':20},
        'second_expiry_cohort':extra['final-soak'][0].get('later_expiry_probe')=={'held':20,'released':20} and extra['final-soak'][0].get('expiry_sql_probe',{}).get('completed')==40,
        'post_load_consistency':all(r.get('consistency') and not any(r['consistency'].values()) for r in final+sum(extra.values(),[])),
        'background_exports_complete':all(r.get('background_export_result',{}).get('csv_lines')==100001 for r in background),
        'background_no_unexpected_error_or_rejection':all(r['errors']['rate']<.001 and r.get('rejected',0)==0 and r.get('consistency') and not any(r['consistency'].values()) for r in background),
        'background_reads_and_writes_within_gate':all(r['groups'][g]['p(95)']<=(10 if g in ('orders','floor') else 500) for r in background for g in ('orders','floor','reservation','cancellation')),
        'background_throughput_gain_10_percent':mean_rps(background)>=mean_rps(reference)*1.1,
    }
    output={'method':{'clients':100,'warmup_seconds':120,'sample_seconds':300,'rounds':3,'p95_comparison':'median of three round P95s','load':'80% read operation choices,20% reservation choices,successful reservation paired with cancellation','fixture':'100000 users,100 parks,100000 spaces,1000000 entries,1000000 orders,200000 recharges; isolated simulation'},'gates':gates,'matrix':{k:[sanitize(r) for r in v] for k,v in matrix.items()},'additional':{k:[sanitize(r) for r in v] for k,v in extra.items()}}
    docs=ROOT/'docs'
    docs.mkdir(exist_ok=True)
    text=['# 容量锁优化与后台消息：本轮实测报告','',
          '本报告保留所有回退与失败项。历史三版本报告见[上一轮报告](performance-report.md)，其数字不与本次同场复测混算。精确镜像、轮次和配置见[脱敏汇总](performance-summary-round3.json)。','',
          '## 测试条件','',
          '- 固定 k6 0.57.0，100 VU 闭环，每版预热120秒、采样300秒、重复3轮；表中P95为三轮P95的中位数，并非合并样本总体P95。',
          '- 操作选择80%读取、20%预约，成功预约配对取消；实际HTTP写比例按原始计数报告。分页只测第1页20条。',
          '- 既有独立百万级模拟数据，日常数据库未灌入测试数据。Java17、MySQL8.4.4、Hikari20、MySQL缓冲池1GiB、JVM堆1GiB。',
          '- 基础分布为10万用户、每用户10条订单和10条入场历史，100个停车场各1000车位且单楼层；模拟账号默认非会员、余额10000。主负载只包含统计、用户订单第1页、楼层状态、预约/取消，不代表深分页、任意包含搜索、支付或出入场的吞吐。其他交易路径由并发集成测试验证正确性。',
          '- 三版本串行使用同一隔离项目，其他本机服务保持运行；未隔离操作系统后台负载。该实验不证明生产用户数或生产容量上限。',
          '- 每个场景标签开始前恢复相同的基础模拟数据与空闲车位；同一标签多轮中的新预约/取消历史会累积，跨标签清理。三轮不是每轮全库重建。',
          '- 候选主流程不依赖RabbitMQ，吞吐收益归于数据库锁、容量计数和准入改造；消息专项测试证明可靠性，不能据此宣称RabbitMQ提高预约吞吐。','',
          '## 逐轮结果','',
          '|版本/轮次|RPS|统计P95/ms|订单P95/ms|楼层P95/ms|预约P95/ms|取消P95/ms|非预期错误|',
          '|---|---:|---:|---:|---:|---:|---:|---:|']
    for label,rows in matrix.items():
        for r in rows:text.append('|'+LABELS[label]+'/'+str(r['round'])+'|'+f"{r['rps']:.2f}|"+'|'.join(f"{r['groups'][g]['p(95)']:.2f}" for g in GROUPS)+f"|{r['errors']['count']}|")
    text+=['','|指标|上轮21d3012|当前1867459|候选|本轮相对当前|','|---|---:|---:|---:|---:|',
           f'|平均RPS|{mean_rps(reference):.2f}|{mean_rps(current):.2f}|{mean_rps(final):.2f}|{change(mean_rps(current),mean_rps(final)):+.2f}%|']
    for g,name in GROUPS.items():text.append(f'|{name}P95/ms|{median(reference,g):.2f}|{median(current,g):.2f}|{median(final,g):.2f}|{change(median(current,g),median(final,g)):+.2f}%|')
    text+=['',f'候选相对同场复测的21d3012吞吐变化为 **{change(mean_rps(reference),mean_rps(final)):+.2f}%**。',
           '', '|版本|采样请求总数|成功预约/秒|成功取消/秒|实际HTTP写比例|拒绝|非预期错误|',
           '|---|---:|---:|---:|---:|---:|---:|']
    for label,rows in matrix.items():
        requests=sum(r['requests'] for r in rows);seconds=sum(r['seconds'] for r in rows)
        text.append(f"|{LABELS[label]}|{requests:,}|{sum(r.get('committedReservations',0) for r in rows)/seconds:.2f}|{sum(r.get('committedCancellations',0) for r in rows)/seconds:.2f}|{sum(r['writes'] for r in rows)/requests*100:.2f}%|{sum(r.get('rejected',0) for r in rows)}|{sum(r['errors']['count'] for r in rows)}|")
    text+=['','## 延迟分布、连接与资源','',
           '|候选接口/轮次|P50/ms|P95/ms|P99/ms|','|---|---:|---:|---:|']
    for r in final:
        for group,name in GROUPS.items():
            values=r['groups'][group]
            text.append(f"|{name}/{r['round']}|{values['med']:.2f}|{values['p(95)']:.2f}|{values['p(99)']:.2f}|")
    timers={'hikaricp.connections.acquire':'获取JDBC连接', 'parking.admission.wait':'事务前排队', 'parking.flow.lock.acquire':'行锁查询（含SQL/网络）', 'parking.flow.capacity.update':'容量条件更新（含等待）', 'parking.flow.commit.window':'应用提交观察窗口', 'parking.flow.transaction.duration':'事务总窗口（含死锁重试）'}
    text+=['','|整轮均值/ms|上轮21d3012|当前1867459|候选|','|---|---:|---:|---:|']
    for metric,name in timers.items():
        values=[timer_mean(rows,metric) for rows in (reference,current,final)]
        text.append('|'+name+'|'+'|'.join('未提供' if value is None else f'{value:.3f}' for value in values)+'|')
    text+=['','|整轮累计值|上轮21d3012|当前1867459|候选|','|---|---:|---:|---:|']
    for key,name in [('sql_count_including_warmup_setup','SQL次数'),('sql_seconds_including_warmup_setup','SQL累计执行秒')]:
        text.append('|'+name+'|'+'|'.join(f'{sum(r.get(key,0) for r in rows):,.2f}' for rows in (reference,current,final))+'|')
    for service in ('redis','redis-cache'):
        text.append('|'+service+'命令总数|'+'|'.join(f"{sum(sum(r.get('redis_commands_including_warmup_setup',{}).get(service,{}).values()) for r in rows):,}" for rows in (reference,current,final))+'|')
    for metric,name in [('parking.cache.hit','缓存命中'),('parking.cache.miss','缓存未命中'),('parking.flow.transaction.retry','事务死锁重试'),('hikaricp.connections.timeout','JDBC获取超时')]:
        text.append('|'+name+'|'+'|'.join(f"{sum(r.get('server_metrics_including_warmup_setup',{}).get(metric,{}).get('COUNT',0) for r in rows):,.0f}" if any(metric in r.get('server_metrics_including_warmup_setup',{}) for r in rows) else '未记录' for rows in (reference,current,final))+'|')
    text+=['','未记录表示该版本未提供此计数器，或本轮未创建按事件注册的计数器；不等同于监控系统已验证该值为0。']
    text+=['','|版本/服务|CPU中位/%|CPU峰值/%|内存峰值/MiB|采样数|','|---|---:|---:|---:|---:|']
    for label,rows in matrix.items():
        for service,values in resource_summary(rows).items():
            text.append(f"|{LABELS[label]}/{service}|{values['cpu_median_percent']:.2f}|{values['cpu_peak_percent']:.2f}|{values['memory_peak_mib']:.2f}|{values['samples']}|")
    text+=['','Docker CPU的100%表示一个逻辑核。上述SQL、Redis、缓存与资源统计包含预热、登录和收尾，三版完成请求数不同；不能用累计总量宣称单位请求成本下降。应用行锁计时不等于MySQL内部锁等待；未采集到的版本明确标为未提供。',
           '', '## 单变量与参数短对照','',
           '|变体/轮次|许可/桶|预热/采样秒|RPS|楼层P95/ms|预约P95/ms|取消P95/ms|非预期错误|',
           '|---|---|---|---:|---:|---:|---:|---:|']
    short=[]
    for label in ('admission-only','final-single-counter','screen16-4','screen16-8','screen16-12','screen16-0'):
        for path in sorted(RAW.glob(label+'-*.json')):
            if not re.fullmatch(re.escape(label)+r'-\d+\.json',path.name):continue
            r=json.loads(path.read_text());short.append(sanitize(r));config=r.get('experiment',{})
            text.append(f"|{label}/{r['round']}|{config.get('permits','见原始制品')}/{config.get('capacity_buckets','见原始制品')}|{r['warmup']}/{r['seconds']}|{r['rps']:.2f}|{r['groups']['floor']['p(95)']:.2f}|{r['groups']['reservation']['p(95)']:.2f}|{r['groups']['cancellation']['p(95)']:.2f}|{r['errors']['count']}|")
    output['short_experiments']=short
    text+=['','短对照含启动、磁盘和提交长尾波动，早期screen还使用不同候选镜像；只用于提出和淘汰配置，不能替代完整三轮发布门槛或精确分解各项改动的收益。单计数器仍承受容量热点，关闭准入的早期候选出现死锁且读取退化，均保留失败记录。']
    output['mysql_lock_diagnostics']=lock_diagnostics
    text+=['','## MySQL锁等待诊断','',
           '以下是独立的单轮30秒预热/60秒采样诊断，额外每秒左右读取一次performance_schema，计数还包含启动和收尾。它与前面的无锁表轮询三轮测试分开保存。Innodb全局累计差值来自隔离MySQL；快照COUNT(*)统计等待请求与阻塞者之间的关系，一名等待者可能对应多名阻塞者。重复快照合计不等于独立锁等待事件数或唯一等待事务数。',
           '', '|变体|许可/桶|MySQL行锁等待总计/ms|行锁等待次数|死锁数|快照中最多阻塞关系|',
           '|---|---|---:|---:|---:|---:|']
    for row in lock_diagnostics:
        delta=row['mysql_status_delta']
        text.append(f"|{row['label']}|{row['permits']}/{row['buckets']}|{delta.get('Innodb_row_lock_time','未采集')}|{delta.get('Innodb_row_lock_waits','未采集')}|{delta.get('Innodb_deadlocks','未采集')}|{row['peak_blocking_relationships']}|")
    text+=['','各表/索引等待快照合计见JSON的 `mysql_lock_diagnostics`。这些总量对应不同的成功写入量，不直接计算单位请求节省或将全部等待归因于容量表。本次诊断脚本通过SHOW GLOBAL STATUS请求死锁计数，但MySQL8.4未返回该变量，因此标为未采集，不能写成0；脚本后续改用INNODB_METRICS供复现，不追补本次无法还原的起点计数。']
    output['background']={'rounds':[sanitize(r) for r in background],'idle_cost':background_cost}
    text+=['','## RabbitMQ后台任务与资源成本','',
           '主流程三轮关闭后台任务；以下在同一候选镜像、相同100个业务VU和8许可/16桶配置下开启RabbitMQ，每轮额外提交一次10万用户导出任务。它是消息开关加导出任务的成本观察，不是严格单变量的中间件收益证明。接收响应与文件完成时间必须分开理解。',
           '', '|轮次|业务RPS|预约P95/ms|取消P95/ms|导出接收/ms|任务至文件就绪/ms|CSV行数|CSV字节数|',
           '|---|---:|---:|---:|---:|---:|---:|---:|']
    for r in background:
        accepted=r['background_export_submission'];ready=r['background_export_result']
        text.append(f"|{r['round']}|{r['rps']:.2f}|{r['groups']['reservation']['p(95)']:.2f}|{r['groups']['cancellation']['p(95)']:.2f}|{accepted['acceptance_ms']:.2f}|{ready['ready_ms']:.2f}|{ready['csv_lines']:,}|{ready['csv_bytes']:,}|")
    text+=['',f"关闭后台任务的平均业务RPS为{mean_rps(final):.2f}，开启且每轮执行一次导出时为{mean_rps(background):.2f}（{change(mean_rps(final),mean_rps(background)):+.2f}%）。三次任务提交P95没有足够样本，不能拿三次接收时间宣称稳定的导出P95。",'',
           '|开启后台任务/服务|CPU中位/%|CPU峰值/%|内存峰值/MiB|','|---|---:|---:|---:|']
    for service,values in resource_summary(background).items():
        text.append(f"|{service}|{values['cpu_median_percent']:.2f}|{values['cpu_peak_percent']:.2f}|{values['memory_peak_mib']:.2f}|")
    text += ['',f"任务完成后RabbitMQ数据目录占用{background_cost['rabbitmq_data_kib']:,}KiB；包含Broker元数据，不能全部当作消息载荷。消息内只含事件ID，空载快照中后端AMQP连接{background_cost['rabbitmq_application_connections']}个、channel合计{background_cost['rabbitmq_application_channels']}个；投递单线程，消费并发为1。连接/线程成本包含在资源表中。",'',
             f"另在空载时完整下载相同10万用户的同步CSV，响应完成耗时{background_cost['sync_export_ms']:.2f}ms、{background_cost['sync_csv_lines']:,}行、{background_cost['sync_csv_bytes']:,}B。空载同步下载与负载中异步受理/后台生成条件不同，不计算导出提速百分比；异步接口缩短的是前台等待，文件仍由后台执行数据库查询和写盘。"]
    text+=['','吞吐变化为正代表提升，延迟变化为负代表改善。拒绝、冲突和未启动的开放模型迭代不计为成功业务。','',
           '## 附加场景','',
           '|场景|采样秒|请求RPS|成功预约/秒|成功取消/秒|预约P95/ms|取消P95/ms|预期冲突|拒绝|未启动迭代|非预期错误|',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for label,rows in extra.items():
        r=rows[0];text.append(f"|{label}|{r['seconds']}|{r['rps']:.2f}|{r.get('committedReservations',0)/r['seconds']:.2f}|{r.get('committedCancellations',0)/r['seconds']:.2f}|{r['groups']['reservation']['p(95)']:.2f}|{r['groups']['cancellation']['p(95)']:.2f}|{r.get('conflicts',0)}|{r.get('rejected',0)}|{r.get('droppedIterations',0)}|{r['errors']['count']}|")
    text+=['','附加场景各测一轮，不能冒充三轮稳定结论。同车位竞争场景的预约延迟含快速返回的预期冲突，不能当作成功预约专属P95；成功业务量单独列出。到达率阶梯为200/600/1200次操作迭代每秒，每段100秒（含1秒过渡），预分配100VU、最多400VU，成功预约另配取消HTTP请求。表中是三个阶段的平均请求RPS，不是最高持续容量。开放模型的未启动迭代包含发生器调度和VU上限约束，不等于已发出的HTTP失败或后端拒绝。60分钟场景在第0和20分钟分别持有20条真实30分钟预约，两批释放检查：'+str(extra['final-soak'][0].get('expiry_probe'))+' / '+str(extra['final-soak'][0].get('later_expiry_probe'))+'；SQL核对：'+str(extra['final-soak'][0].get('expiry_sql_probe'))+'。两批准备请求不计入采样请求数，但消耗测试资源。','',
           '## 发布门槛','']
    exceeded=[]
    if extra['final-soak'][0].get('collection_recovery'):
        text+=['长测采集限制：k6已完整写出3600秒摘要，但收尾进程中断，未保存该轮SQL/Redis计数、资源采样和HTTP teardown输出。两批预约由此前持有检查点及恢复时只读SQL核对；持久事件的completed_at证明40条真实1800秒预约均在摘要落盘前完成，最大延迟见上表。容量/关联为恢复时最终快照。未补造丢失计数，也不将本轮长测用于资源成本比较；详细来源见JSON的collection_recovery。','']
    for label,rows in extra.items():
        for r in rows:
            for group,limit in (('orders',10),('floor',10),('reservation',500),('cancellation',500)):
                if r['groups'][group]['p(95)']>limit:exceeded.append(f"{label} {GROUPS[group]}P95={r['groups'][group]['p(95)']:.2f}ms（参考目标{limit}ms）")
    if exceeded:text+=['附加场景未达到同名主场景参考延迟的项目：'+'；'.join(exceeded)+'。附加场景请求速率、竞争和并发上限不同，单独列出，不将主对照三轮通过表述为所有负载都达标。','']
    for name,passed in gates.items():text.append('- '+name+'：'+('通过' if passed else '**未通过**'))
    text+=['','## 正确性、边界与复现','',
           '- 后端61项、前端11项（2项请求测试、9项组件测试）、生产容器浏览器10项通过。真实RabbitMQ停机/重启后持久化任务恢复，停机期间预约取消可用；第三方真实付款与OCR/AI调用未执行。',
           '- 本轮前端只增加导出任务状态和下载入口，退出弹窗375/768/1440视口下的343/400/400px宽度通过浏览器回归；未重新采集LCP/CLS或三版本首屏脚本对照。此前68.8%首屏压缩脚本降幅属于历史改版，不归因于本轮事务或RabbitMQ改造。',
           '- 容量与占用关联核对在每个场景标签的全部轮次结束后执行；三轮结果中保存的是同一结束快照，不能声称逐轮或全过程都进行了数据库快照检查。并发期间的业务不变量另由真实数据库集成测试覆盖。',
           '- 迁移重跑、1/16桶切换与差异阻断已验证；部署前仍需备份并停止所有写入实例。',
           '- SQL次数、SQL累计耗时、服务端计数和资源采样包含预热、登录及收尾，不能除以采样请求数冒充精确单位请求成本。',
           '- 部分k6接口摘要的最小耗时出现负值，原值保留，无法从聚合摘要还原其发生次数；本报告不使用最小值判断达标，尚未对这些极低耗时样本做另一计时工具的逐请求交叉校验。',
           '- RabbitMQ三轮原始负载与遥测均完成后，后处理脚本曾误将辅助资源JSON当作结果摘要而停止；已改为按确切轮次文件处理，只重做只读导出和一致性核对。原始流量数据、逐轮stdout/stderr与资源采样均保留，没有挑选或替换轮次。',
           '- 应用容量SQL耗时包括网络和锁等待；提交窗口是Spring应用观察值，不等同于MySQL内部精确持锁/fsync耗时。',
           '- 实验性关闭准入的短测发生死锁且读取回退，保留原始失败数据；生产不使用该配置。',
           '- 在准备好的隔离fixture与镜像上执行 `python scripts/run-round3-matrix.py`，完成后依次执行 `python scripts/verify-round3-background.py`、`python scripts/report-round3.py`。后台验证脚本顺序运行MQ三轮、空载导出成本与三组锁诊断；已有证据时拒绝覆盖。单个变体用 `python scripts/benchmark-round3.py --image <image> --label <unique-label> --permits 8 --buckets 16`。不要对日常部署执行模拟数据准备或压测。',
           '- 消息、导出接口、容器配置与回退步骤见[后台任务说明](background-tasks.md)。','']
    (docs/'performance-summary-round3.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (docs/'performance-report-round3.md').write_text('\n'.join(text),encoding='utf-8')
    if all(gates.values()):
        submissions=[r['background_export_submission']['acceptance_ms'] for r in background]
        ready_times=[r['background_export_result']['ready_ms'] for r in background]
        soak=extra['final-soak'][0]
        resume=['# 本轮Java后端简历项目亮点','',
                '以下表述使用本轮实际完成的同场对照；可按简历篇幅选用。测试为独立模拟数据、单机Docker部署，不代表真实生产在线用户数或容量上限。','',
                '## 可直接使用','',
                f"- 基于Spring Boot、MySQL和Redis优化停车预约事务，采用停车场资料共享锁、16桶强一致余位计数及事务前全局有界准入；在100万订单、100万入场记录、100 VU混合负载下，将三轮平均吞吐从{mean_rps(current):.2f}提升至{mean_rps(final):.2f}请求/秒（提升{change(mean_rps(current),mean_rps(final)):.2f}%），采样{sum(r['requests'] for r in final):,}次请求未出现非预期错误。",
                f"- 将容量更新收敛至事务末尾，保留用户/车位唯一约束与统一锁顺序；预约P95（三轮中位数）由{median(current,'reservation'):.2f}ms降至{median(final,'reservation'):.2f}ms，取消由{median(current,'cancellation'):.2f}ms降至{median(final,'cancellation'):.2f}ms，同时用户订单和楼层查询P95分别保持在{median(final,'orders'):.2f}ms、{median(final,'floor'):.2f}ms。",
                f"- 通过事务Outbox、RabbitMQ持久化消息、Publisher Confirm与手动ACK实现幂等后台任务及死信重放；在100 VU业务负载下，三次10万用户导出任务受理耗时{min(submissions):.2f}–{max(submissions):.2f}ms，文件就绪耗时{min(ready_times):.2f}–{max(ready_times):.2f}ms，真实验证Broker停机期间预约可用及重启后任务恢复。",
                f"- 基于Redisson延迟队列、持久化事件表及完成事件快速确认优化预约到期处理；在60分钟、100 VU稳态测试中完成{soak['requests']:,}次采样请求，两批共40条真实30分钟预约全部释放，数据库容量及占用关联核对无差异；通过61项后端回归，覆盖并发、权限、重复支付和中间件故障。",'',
                '## 指标解释与复现','',
                f"- 主对照每版100 VU、预热120秒、采样300秒、3轮；80%读取操作选择、20%预约选择，成功预约配对取消。候选实际HTTP写比例为{sum(r['writes'] for r in final)/sum(r['requests'] for r in final)*100:.2f}%。",
                '- P95采用三轮各自P95的中位数，完整报告列出每一轮；不把中位数称为合并请求的总体P95。',
                f"- 对照1867459描述本轮收益；相对同场21d3012平均吞吐提升{change(mean_rps(reference),mean_rps(final)):.2f}%。原始版历史数据另见旧报告，不跨测试批次计算累计倍数。",
                '- 百万数据是独立生成的历史记录，10万账号是模拟用户。分页主要测第1页20条，不能外推深分页、任意包含搜索或生产用户量。',
                '- RabbitMQ的3次导出耗时是观测区间，样本不足以宣称导出P95；前台接收任务与文件生成完成是两个指标。预约吞吐提升来自事务设计，不能归因于RabbitMQ。',
                '- 60分钟测试只跑1轮；40条到期探针与持续预约/取消负载分别记录。结束时的数据库一致性快照不代表对全过程做了连续快照验证。',
                '- 复现：从对应Git提交构建历史镜像，准备隔离fixture后运行 `python scripts/run-round3-matrix.py`；消息成本和锁诊断命令见本轮完整报告。',
                '- [逐轮性能与资源报告](performance-report-round3.md) · [脱敏证据](performance-summary-round3.json) · [后台任务与容量实现](background-tasks.md) · [历史简历材料](resume-highlights.md)','',
                '## 面试追问','',
                '需要解释：共享资料锁与管理变更如何协调；分桶为何仍然强一致；为什么不把预约改成异步排队；事务前准入如何保留连接预算；1213死锁与不确定提交为何区别重试；Outbox确认丢失与消费中断如何恢复；文件生成如何通过租约令牌防止旧执行覆盖；模拟数据、RPS、并发与生产容量的边界。','']
        (docs/'resume-highlights-round3.md').write_text('\n'.join(resume),encoding='utf-8')
    print(json.dumps({'gates':gates,'candidate_rps':mean_rps(final),'gain_over_released':change(mean_rps(reference),mean_rps(final))},ensure_ascii=False))
