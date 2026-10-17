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

缓存使用约 90%–100% 的 TTL 随机偏移，不超过声明的最长有效期。64 个固定锁分组合并热点回源；Redis 或本地缓存命中不进入回源互斥区。

所有可重建缓存键包含全局随机版本，提交后的资料修改切换版本，同时清理本地缓存。旧请求即使迟到回填，也只写入旧版本键。版本键被 LFU 淘汰后重新生成随机版本，避免重用旧数据；统计和目录查询另外包含角色、管理员及查询范围。全局失效比按资源失效更粗，但消除了失效故障期间维护无界待办集合的问题，也能让停车场归属变更后的统计及时重新计算。

Caffeine 3.1.8 是可关闭的本地一级缓存，仅允许 `catalog`、`rules`、`layout:*`，最长 1 秒、默认 16MiB 加权预算。权重按字符串存储估算，并非 JVM 精确堆大小；返回时反序列化为独立对象，调用者不能修改缓存内容。Redis TTL 预留本地缓存时间，避免叠加突破声明的新鲜度上限。本轮开关对照的读取 P95 中位数改善 10.66%、写入 P95 中位数最大回退 2.22%，Compose 默认启用；多实例部署应关闭本地层或另行实现跨实例失效。

缓存连接超时 500ms、命令超时 200ms，失败后退避 10 秒并回源。失效失败会标记整个缓存命名空间不可用、清理本地层；恢复后先切换 Redis 版本，成功后才重新允许命中。余额、支付、授权、实际计费和实时占用不使用这些缓存。Token Redis 故障返回 503，前端保留会话；缓存淘汰和清空不影响 Token 或预约消息。

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

## 停车场查询及退出弹窗修复

停车场分页原 XML 的五处条件将单引号写成了带反斜杠的 Java 字符串形式；XML 不需要这种转义，OGNL 解析因此失败。统一使用合法表达式，Mapper 显式命名参数，查询字段使用表别名。测试不仅启动 MyBatis，还解析每个 Mapper 的动态表达式，并通过真实 HTTP 验证空条件、空串、组合查询及两个管理员角色的 CRUD。

全局确认弹窗样式原本将组件最大宽度扩大至视口宽度减 32px，而 Element Plus 本身使用 `width:100%`，导致桌面窗口几乎铺满屏幕。最大宽度现为 `min(400px,100vw - 32px)`，不改变业务编辑弹窗。375/768/1440 屏幕下，实测宽度由 343/736/1408px 变为 343/400/400px。

## 连接池等待与事务前排队

`ParkingAdmission` 使用 64 个固定公平信号量分组，避免按任意停车场 ID 创建无界锁对象。默认每组 4 个许可，并受 `DB_POOL_SIZE/4` 上限约束；排队超过 128 个或等待超过 5 秒返回 503。分组哈希可能使不同停车场共享许可，这是一项有界资源取舍，不代表新增分布式锁。

查询车位或记录所属停车场后，先等待许可，再通过 `TransactionTemplate` 启动车辆状态事务。原有数据库用户锁、停车场锁及唯一约束继续决定正确性；多实例仍由数据库协调。已有外层事务时跳过应用许可，保留原事务传播，避免在持有数据库行锁后等待应用许可。网页预约、AI 预约及延迟消费者共用这一入口。

Micrometer 增加 MyBatis 映射耗时、缓存命令与编解码、单飞等待、应用许可等待、数据库行锁获取及锁定后的事务窗口。行锁获取指标包含查询及网络时间，不能直接当作 MySQL 纯阻塞时间；Hikari 获取连接指标也与连接池超时分开统计。日志仅包含请求 ID、接口路径、映射标识、异常类别与来源，不输出 SQL 参数、异常正文或密钥。

`parking.flow.park.lock.hold` 实际记录从停车场锁定查询返回至事务同步完成的窗口，包含提交后回调；它是应用侧近似观测，不能当作数据库内部精确持锁时间。需要精确阻塞链时再使用受控的 MySQL Performance Schema 诊断。

## k6 三版本统一测量

`benchmark-k6.py` 固定使用 `grafana/k6:0.57.0`，负载容器位于同一专用 Docker 网络，最多使用 4 CPU 和 1 GiB 内存，记录负载容器本身的资源，避免将发生器饱和误判为服务瓶颈。原始版 `797313c`、上轮发布版 `21d3012`、本轮候选版分别选择对应镜像及索引集合。

```sh
# 从已核对的 Git 提交导出原始版、上轮版并构建，禁止将当前镜像改名冒充旧版本：
python scripts/prepare-benchmark-versions.py --build
python scripts/benchmark.py prepare
docker compose -p parking-benchmark --env-file .work/benchmark.env -f compose.benchmark.yml up -d --wait
# 仅首次空库运行 seed，已有固定数据时跳过：
python scripts/benchmark.py seed
docker compose -p parking build backend frontend
python scripts/profile-space-index.py
python scripts/benchmark-matrix.py
# 已完成独立开关门槛、但完整测试中断时，可保留原记录后重跑完整三版本：
python scripts/benchmark-matrix.py --full-only
# 完整负载结束后，再做冷热统计与 SQL 探针，避免探针登录撤销压测 Token：
python scripts/benchmark-k6.py --version candidate --local --mode cold --rounds 1 --label candidate-cold
python scripts/benchmark-k6.py --version candidate --local --mode warm --rounds 1 --label candidate-warm
python scripts/profile-queries.py
```

完整统计页面的 SQL 次数在验证栈单独测量：先按 README 运行浏览器验证并设置 `PLAYWRIGHT_BROWSERS_PATH`，再从根目录执行 `node scripts/measure-dashboard.mjs`。脚本仅访问 `parking-verification`，清空其业务缓存并等待一秒本地缓存到期后重载统计页；账户资料、停车场选项和统计都计入，不只统计单个接口。

脚本先运行 Caffeine 开关对照（各三轮，预热 10 秒、采样 30 秒），使用楼层 P95 三轮中位数及预约、取消的 P95 中位数作为门槛：读取至少改善 10%、写入最大回退不超过 5%。完整三版本各三轮仍采用预热 120 秒、采样 300 秒，不能用短对照替代完整性能结果。

原始版使用原收入、订单历史和楼层接口，其他版本使用统一统计、分页和实时状态接口。操作随机种子固定，但闭环负载下较慢接口会占据更多并发客户端；写入比例按实际请求另行计算。采样按完成时间限定在五分钟窗口内，排除预热及结束后的收尾请求。

每轮结果记录业务错误率、P50/P95/P99、镜像 ID、业务日期、容器资源、服务器指标、SQL 与 Redis 命令量。SQL/Redis 计数和服务端累计指标覆盖整轮及固定的登录准备、预热和指标读取，报告明确使用该口径；不将这些全程计数除以采样请求数冒充采样窗口的每请求开销。三轮分位数逐轮列出，并使用三轮中位数比较，不对 P95 求平均冒充总体 P95。

完整比较要求九轮使用同一业务日期和同一测量脚本，每个版本的三轮镜像 ID 相同。中断或跨日记录留在本地归档，不与新一批结果拼接。脚本单独统计采样窗口的读、写次数，确认两者之和等于总请求数；公开汇总不包含 Token、凭据或业务响应。

候选楼层覆盖索引在 10 万车位临时副本上，查询从约 0.91ms 降至 0.18ms，但 5,000 行状态更新中位数从 39.893ms 增至 46.028ms（+15.38%），超过写入成本上限，因此不新增生产索引或迁移。所有临时副本已清理。
