# 全面优化说明

## 业务与权限

普通管理员管理所属停车场、车位、出入场、停车订单和运营统计。平台用户资料、用户导出、密码重置、充值流水与管理员管理仅超级管理员可访问。前端菜单和路由同步限制，后端仍独立校验；统计的所属条件直接进入 SQL。

预约、入场、取消、过期、离场继续以 MySQL 为最终依据，锁顺序保持为用户、停车场、业务记录及车位。OCR 返回后持有停车场锁再检查当前归属；事务内授权采用当前锁定读取，避免重复读旧快照放行刚转出的停车场。占用和释放成功后，在同一事务中条件增减余位；地图结构修改才重新汇总容量。唯一约束及占用记录 ID 保证重复事件不会释放其他预约。

会员开通、续期锁定用户，累加有效期，保留现有会员规则。用户存在停车或充值记录时不能直接删除。管理员自助修改密码校验原密码，删除管理员、修改或重置密码都会撤销相应 Token。

## 查询与接口

| 接口 | 行为 |
| --- | --- |
| `GET /dashboard/summary` | 默认近 7 天，支持 1–31 天及可管理停车场；返回 `counters`、`series`、`updatedAt`、`timezone` |
| `GET /orders/userPageList` | 当前用户订单分页，`pageNum=1`、`pageSize=20` |
| `GET /recharges/userPageList` | 当前用户充值分页，同上 |
| `GET /rule/pageList` | 规则分页，原规则选项接口保持可用 |
| `GET /parking/catalog` | 按角色范围缓存停车场选项，不作为余位或授权依据 |
| `GET /spaces/layout` | 楼层静态布局，不包含占用者身份 |
| `GET /spaces/availability` | 直接读取当前占用状态 |
| `GET /system/capabilities` | 已登录用户读取第三方功能开关，不返回密钥 |

分页统一返回 `Result.data = {total, items}`，每页最多 100 条，按业务时间和 ID 稳定倒序。原用户订单、充值列表最多允许返回 100 条；超限返回明确业务错误，客户端需改用分页。导出使用 ID 游标，每批 500 条，不重复计算总数。

统计改为数据库聚合，超级管理员冷加载业务查询为 6 条，普通管理员为 3 条；选择具体停车场时可能增加一次归属校验。无记录日期由服务补零，不按天循环查询。时间区间使用左闭右开边界，部署业务时区为 Asia/Shanghai。

包含搜索继续使用 `LIKE '%关键词%'`，普通 B-tree 索引不能消除这类扫描。性能报告分别列出带用户/停车场范围的查询和聚合，不将任意关键词搜索宣称为索引直达。

## MySQL 索引

`003-performance-indexes.sql` 增加用户/停车场与业务时间组合索引、空闲车位选择索引、会员到期和充值状态时间索引、会话用户时间索引，并复用坐标唯一索引支持楼层读取。脚本检查索引名称及列顺序、升降序相同的已有索引，避免重复创建；可重复执行，不清空或改写业务记录。全新初始化已包含同组索引。

索引会增加写入、存储和维护开销。对比通过固定数据集的 `EXPLAIN ANALYZE` 和包含预约/取消的混合负载进行，不能只根据索引存在认定性能改善。原有有效用户、有效车位与车位占用关联唯一索引保留。车位坐标唯一索引调整为“停车场、楼层、行、列”，复用它支持楼层读取；已被复合索引覆盖的三个外键单列索引移除，外键约束仍保留。

MySQL 缓冲池默认 1 GiB，最大连接数 100；后端 Hikari 默认最大 20、最小空闲 5，连接等待 3 秒，MyBatis 查询超时 5 秒。它们是本机起始配置，部署时应结合可用内存、慢查询和连接池等待调整。

## Redis 缓存一致性与故障

| 实例/内容 | 有效期或策略 |
| --- | --- |
| `redis`：Token、预约队列 | 512 MiB、noeviction、AOF everysec |
| `redis-cache`：可重建读缓存 | 256 MiB、allkeys-lfu、不持久化 |
| 计费规则展示与停车场选项 | 10 分钟以内 |
| 楼层静态布局 | 5 分钟以内 |
| 统计 | 30 秒以内，返回原始计算时间 |
| 不存在的缓存对象 | 30 秒以内 |

缓存使用约 90%–100% 的 TTL 随机偏移，不超过声明的最长有效期。64 个固定锁分组合并本进程的热点回源，避免为任意键创建无限数量的锁。

资源缓存键包含随机版本。读取在加载数据库前取得版本；事务提交后改变版本，回滚不变。并发的旧读取即使迟到，也只会写入旧版本键。版本键被 LFU 淘汰后重新生成随机版本，避免重用旧版本数据。统计键另外包含角色、管理员、停车场、天数和日期，防止权限范围混用。停车场不存在时也会短暂缓存空值；创建或编辑停车场改变全局目录版本，让空值与已有资料同时失效。

缓存连接超时 500ms、命令超时 200ms，失败后退避 10 秒并回源。若提交后的失效操作因故障失败，旧展示数据可能保留到 TTL 到期，因此余额、支付、授权、实际计费与车位占用均不使用这些缓存。Token Redis 故障返回 503，前端保留会话；缓存 Redis 的淘汰或清空不会清除 Token 与预约消息。

预约恢复按每批 500 个事件加载，处理中消息使用 Redis 原子转移，不读取整队列到应用内存。启动、重连校准和单事件失败重试保留，不增加周期性预约表扫描。会员与未付款充值维护使用有索引的分批条件更新。

## 前端结构

`AppLayout` 提供角色导航、折叠菜单、面包屑及移动端导航。`RecordPage`、`SearchBar`、`PagedTable` 和 `usePagedQuery` 复用表单、分页、取消请求、请求版本保护及失败重试。搜索重置页码，删除后自动调整到有效页。

`SpaceWorkspace` 供管理和预约共用，页面可见时每 15 秒刷新。`ParkingMap` 使用坐标 Map 索引、固定视口 Canvas、可视区域绘制和动画帧合并，缩放后仍按正确坐标命中；列表提供替代选择方式。`TrendChart` 按需加载，尺寸变化时重绘，卸载时销毁。

Element Plus 和 ECharts 按需引入，移除无引用的 Font Awesome。Nginx 压缩静态内容，为哈希资源设置长期缓存，入口 HTML 重新校验；普通 API 与 AI 流接口分别配置超时和缓冲。

AI 仍为可选能力。会话内容有数量、消息数和闲置时间上限，流式调用超时 60 秒；会话元数据在 MySQL，内容尚未持久化。OCR 调用已有 20 秒总超时，OSS 增加连接/读取超时并限制重试。外部调用本轮不使用真实密钥或真实支付验证。

## 可重复验证

Windows 设置当前终端 JDK 17 后运行：

```powershell
.\scripts\test.ps1 -IncludeBrowser
python scripts/test-service-recovery.py
```

测试项目为 `parking-verification`，和日常部署、性能数据隔离。故障恢复脚本会暂停该验证项目的 Redis/MySQL/应用，不能改为指向日常项目。

百万数据性能测试：

```sh
python scripts/benchmark.py prepare
docker build -f .work/baseline-source/deploy/backend.Dockerfile -t parking-baseline-backend:797313c .work/baseline-source
docker build -f .work/baseline-source/deploy/frontend.Dockerfile -t parking-baseline-frontend:797313c .work/baseline-source
docker compose -p parking-benchmark --env-file .work/benchmark.env -f compose.benchmark.yml up -d --wait
python scripts/benchmark.py seed
python scripts/benchmark.py run --label baseline
# 构建优化版 parking-backend、parking-frontend 后：
python scripts/benchmark.py upgrade
python scripts/profile-queries.py
python scripts/benchmark.py run --label optimized
# 完整压测结束后再运行，避免其他探针干扰负载：
python scripts/profile-index-writes.py
```

标记基线镜像时，必须确认它们实际来自提交 `797313c`，不能把优化镜像重新命名后当作基线。准备脚本从该提交读取原始初始化结构；生成数据前检查用户表为空，否则拒绝灌入。升级阶段只清理该专用栈的合成预约事务和 Redis 状态，保留固定历史数据。

默认每轮 100 并发、预热 120 秒、采样 300 秒，重复 3 轮。操作比例为 80% 读取、20% 预约操作；一次预约成功后配对取消，因此按实际 HTTP 请求数计算时，写请求比例高于 20%。基线统计调用原近七天收入接口，优化版调用包含更多指标的统一概览；历史订单及楼层读取也分别使用对应版本的旧/新接口，属于用户场景比较。

性能输出只包含耗时、计数、脱敏 SQL 摘要和资源用量，保存于 `.work`。发布的汇总见[性能报告](performance-report.md)。
