# RabbitMQ 后台任务与容量优化

本文件描述容量事务与可选后台任务实现。发布与默认参数以[本轮完整性能报告](performance-report-round3.md)的验收结论为准，短测数字不代表生产容量。

## 预约事务

预约、取消、入场、出场同步返回数据库提交结果。Redis/Redisson 继续负责预约到期；RabbitMQ 不参与车位争抢、余额扣减或余位计算。

车辆事务使用 READ COMMITTED、用户行锁、停车场资料共享锁、车位及入场记录锁、容量条件更新。有效用户和车位的唯一索引仍是最终约束。容量更新是最后一条业务 SQL；停车场资料修改、地图编辑和归属变更使用资料排他锁。事务准入位于获取 JDBC 连接之前，采用单进程全局预算，避免不同停车场的哈希分组冲突。

`parking_capacity` 与资料分离，`parking_capacity_total` 在 MySQL 中汇总容量。当前默认16桶，支持一桶对照及按 `CRC32(space_id的十进制字符串) % 16` 固定映射的 16 桶。每次真实占用/释放只更新对应桶，事务失败同时回滚。实时容量不经过 Redis 缓存。`CAPACITY_BUCKETS` 必须与 `capacity_settings` 相同，否则应用拒绝启动。所有写入实例必须使用同一配置。

旧 `parking.park_mount/park_spare` 仅供回退，不再随业务更新；禁止旧后端与当前后端混跑。

## 消息的可靠性

`background_event` 是持久化事件表，业务变化与事件写入同一 MySQL 事务。独立投递线程按到期索引领取任务，用 `SKIP LOCKED` 和租约协调领取。发送持久化消息，检查 Publisher Confirm 和 mandatory return；Broker 不可用时延后重试，SQL 事件不丢弃。

队列携带事件 ID，不携带用户资料、密钥和筛选条件。预约队列对已完成事件的旧消息只做索引点查并确认，不再锁用户或重复写完成时间；缺少事件记录时仍走实际预约状态校验，避免静默丢弃真实到期。消费者从数据库读取任务，使用手动 ACK。成功效果提交后 ACK；数据库故障 NACK 重投，防止未确认消息永久占满 prefetch。每条消息可能被处理多次，通过事件状态、租约令牌及审计主键实现效果幂等，不承诺消息恰好投递一次。

业务执行失败最多尝试 5 次，转入 DEAD_PENDING，再经确认投递至死信队列后标记 DEAD。Broker 故障的发送重试不消耗业务执行次数。过期的投递/执行租约由事件投递器恢复；SENT 超时也重新发送，重复消息由消费者幂等处理。该轮询只处理后台事件，不扫描预约来判断过期。

## 用户导出

只有超级管理员能创建任务、查询状态、下载文件。不同超级管理员也不能下载对方的文件。一次最多保留 3 个未结束任务。支持原有用户名、车牌、车辆颜色及会员状态筛选。

| 接口 | 用途 |
|---|---|
| `GET /admin/background/capabilities` | 查询后台导出开关 |
| `POST /admin/background/exports` | 提交筛选 JSON；请求头 `Idempotency-Key` 为同一次操作的稳定随机标识 |
| `GET /admin/background/exports/{id}` | 查询状态 |
| `GET /admin/background/exports/{id}/file` | 下载已完成的 CSV，不可缓存 |
| `GET /admin/background/dead` | 超级管理员查看最近 100 条失败事件，不返回任务载荷 |
| `POST /admin/background/dead/{id}/replay` | 修复原因后重放失败事件 |

相同管理员、任务类型、请求标识只创建一个任务。导出时记录用户 ID 上界，每批按 ID 游标读取 500 行，避免深分页。期间的资料更新或删除可能反映在后续批次中，文件不是全库同一时刻的快照。CSV 转义换行、引号及公式前缀，不导出密码和 Token。

导出文件写入私有目录，临时文件完成后原子重命名；数据库完成标记受租约令牌保护。文件写入完成后先强制刷新，再原子重命名；租约每30秒续期，避免每500行都产生一次持久化写入。文件保留24小时，过期由数据库时间判断并立即拒绝下载，后台每分钟清理一批；进程中断的孤立文件也会清理。目录必须位于支持同目录原子重命名的本地文件系统/容器数据卷，不映射为公开静态目录。

前端显示处理中、完成、过期和失败；离开页面或页面隐藏时停止状态轮询，返回页面后恢复。关闭后台任务开关时继续使用原 `GET /user/export` 同步导出。

## 管理审计

记录停车场新增/修改/删除及地图结构变更的操作者 ID、操作类型、实体 ID 和业务发生时间。事件不保存完整请求或个人资料。业务回滚不会生成审计，审计消费者重放不会产生重复记录。后台功能关闭时不生成新的审计任务。

## Docker 启用

在本地 `.env` 设置随机 `RABBITMQ_PASSWORD`、`RABBITMQ_USER=parking`、`BACKGROUND_TASKS_ENABLED=true`，执行：

```sh
docker compose -f compose.yml -f compose.background.yml --profile background up -d --build
```

RabbitMQ 镜像固定为 `4.1.4-management` 并锁定摘要；512MiB 是起始容器内存预算。管理界面仅绑定 `127.0.0.1:15672`，AMQP 端口不向宿主机发布。Windows 与 Linux 使用相同 Compose 文件。单机单节点不提供集群高可用。

`rabbitmq-data` 保存消息，`export-data` 保存导出文件。停止时使用同一组 Compose 文件执行 `down`，不加 `-v`。Broker 不可用时核心健康状态仍反映核心服务；后台异常由投递失败、消费失败指标及事件积压单独监控。

## 升级与回退

1. 备份数据库及现有镜像，停止所有后端写入实例。
2. 在目标数据库依次执行 `004-capacity.sql`、`005-background-tasks.sql`、`006-capacity-buckets.sql`。先迁移再启动新后端。
3. `004` 对照实际车位核对容量，存在差异立即停止，不猜测修复。`006` 默认16桶，并需设置 `CAPACITY_BUCKETS=16`；一桶对照时，在同一个 MySQL 会话先执行 `SET @capacity_buckets=1;` 再执行 `006`，同时设置 `CAPACITY_BUCKETS=1`。
4. 重复迁移应保持业务数据和汇总容量不变。切换桶数也必须停止写入；新旧桶模式不能混跑。
5. 回退时再次停止写入，执行 `database/rollback/004-capacity.sql` 将新容量汇总回填旧字段，并用数据库桶数0标记旧写入模式，阻止新后端误启动。核对实际车位后再启动旧镜像。再次升级仍需004/005/006：先核对旧字段与实际车位，再重建派生容量并切回1或16桶。保留新表与消息卷，避免删除尚未处理的任务。

全新初始化使用 `database/init`，默认16桶；已有数据或切换桶数时，先完成第3步再启动后端。迁移与回退不自动清空业务表。

## 诊断

查看后台容器日志和 `background_event` 的状态计数、最老 READY/SENT 时间、DEAD_PENDING/DEAD 数量。`parking.background.publish.failure` 是发送失败次数，`parking.background.consume.failure` 是消费基础设施失败次数；`acknowledged` 包含幂等忽略的重复投递，不能当成完成任务数量。完成任务数量以数据库 DONE 记录为准。`parking.background.pending`、`dead`、`oldest.age` 分别表示未完成的活动任务数、待确认死信或已死信任务数、最老活动任务年龄；数据库不可用时报告NaN，不能当成零。业务失败另记 `parking.background.execution.failure` 与脱敏日志。

`parking.flow.capacity.update` 包含 SQL 执行与锁等待；`parking.flow.commit.window` 是 Spring 提交前至提交后的应用观察窗口，不等同于 MySQL 内部精确 fsync 时间。结合 `performance_schema` 的锁等待快照分析，不能把排队转移当作性能收益。
