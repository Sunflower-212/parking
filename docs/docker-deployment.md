# Docker 部署手册

## 组件与前置条件

| 服务 | 镜像/运行环境 | 数据位置 |
| --- | --- | --- |
| frontend | Node 22 构建，Nginx 1.28.0 提供页面 | 镜像内静态文件 |
| backend | Maven 3.9.11 构建，Java 17 运行 | 默认上传目录仅用于临时识别文件 |
| mysql | MySQL 8.4.4 | `mysql-data` 命名卷 |
| redis | Redis 7.4.2，Token/队列，AOF everysec、noeviction | `redis-data` 命名卷 |
| redis-cache | Redis 7.4.2，256 MiB、allkeys-lfu，只读业务缓存 | 无需备份，可重建 |

Windows 使用运行中的 Docker Desktop，切换为 Linux 容器并启用 WSL2。Linux 安装 Docker Engine 和 Compose 插件。首次构建需要能够访问 Docker Hub、Maven Central 和 npm registry。

开发验证还需要 JDK 17、Node 22/24 和 Python 3；只运行 Docker 时不需要在宿主机安装 Java、Node。

## 新环境部署

在仓库根目录执行：

```sh
python scripts/init-env.py
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

Linux 可将 `python` 换成 `python3`。初始化脚本在 `.env` 不存在时生成随机凭据；已有文件保留原值，仅补充缺失的 `CACHE_REDIS_PASSWORD`。

默认访问 http://localhost:8088。首次超级管理员凭据保存在 `.env`。登录后先配置规则、停车场和车位。后端健康检查为 `/api/actuator/health`。

前端默认绑定 `127.0.0.1`。部署到服务器并需要外部访问时，将 `.env` 中 `HTTP_BIND` 改为 `0.0.0.0`，配置防火墙，并在前置反向代理提供 HTTPS。

```sh
docker compose up -d
docker compose logs --tail=100 backend
```

MySQL 和 Redis 默认没有宿主机端口，后端通过容器服务名连接。不要在后端配置里把数据库或 Redis 主机写成 `localhost`；容器内该地址代表容器自身。

## 配置表

| 变量 | 用途及默认 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL 管理密码；本地生成 |
| `DB_PASSWORD` | parking 数据库账号密码 |
| `REDIS_PASSWORD` | Token/队列 Redis 认证密码 |
| `CACHE_REDIS_PASSWORD` | 独立缓存 Redis 密码 |
| `MYSQL_BUFFER_POOL_SIZE` / `MYSQL_MAX_CONNECTIONS` | 默认 `1G` / `100` |
| `DB_POOL_SIZE` | 默认 `20` |
| `REDIS_STATE_MEMORY` / `REDIS_CACHE_MEMORY` | 默认 `512mb` / `256mb` |
| `JWT_SECRET` | 至少 32 字符的随机签名密钥 |
| `BOOTSTRAP_ADMIN_USERNAME` | 首次管理员名称，3 至 16 位 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 首次管理员密码，8 至 16 位；仅初始化时使用 |
| `HTTP_BIND` / `HTTP_PORT` | 默认 `127.0.0.1` / `8088` |
| `RESERVATION_TIMEOUT` | 默认 `30m`；只影响新预约 |
| `AI_ENABLED` / `OCR_ENABLED` / `ALIPAY_ENABLED` / `OSS_ENABLED` | 可选集成开关，默认 `false` |

更改环境变量后运行 `docker compose up -d`，单纯 `restart` 不会重新注入修改后的环境变量。数据库账号密码已经初始化到持久化卷后，仅修改 `.env` 不会修改库内密码，需要先通过数据库管理操作同步修改。

后端 Docker 使用 `SPRING_CONFIG_NAME=application-example` 读取公开的环境变量模板，真实 `application.yml` 不进入构建上下文或镜像。个人配置文件仍留在本地。

## 可选集成

### DeepSeek

```dotenv
AI_ENABLED=true
AI_AUTOCONFIG_EXCLUDE=
DEEPSEEK_API_KEY=填写本地真实密钥
DEEPSEEK_BASE_URL=https://api.deepseek.com
```

启用时必须清空 `AI_AUTOCONFIG_EXCLUDE`，允许 Spring AI 创建模型客户端。默认模型为 `deepseek-chat`。对话接口为 GET `/api/ai/chat?message=...&chatId=...`，需用户 Token。网关已关闭响应缓冲并延长流式响应超时。

会话消息当前仅保存在后端进程内存，元数据在 MySQL；重启后不会恢复消息内容。

### 百度车牌识别

设置 `OCR_ENABLED=true`、`BAIDU_API_KEY`、`BAIDU_SECRET_KEY`。识别接口使用 HTTPS 和 20 秒超时，访问令牌存入 Redis 并根据有效期缓存。无效令牌被移除，后续请求重新获取。

上传限制为 5MB JPEG/PNG，处理后删除本地临时文件。Nginx 请求体上限为 6MB。

### 支付宝沙箱

设置 `ALIPAY_ENABLED=true` 及 `ALIPAY_APP_ID`、`ALIPAY_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY`、`ALIPAY_NOTIFY_URL`、`ALIPAY_RETURN_URL`。

当前代码使用支付宝沙箱网关。回调地址应是支付宝可访问的 HTTPS 地址，路径为 `/api/user/rechargeNotify`；不能使用仅本机可访问的 localhost 地址。单行 PEM 内容按支付宝 SDK 所需的密钥格式填写。

只有验签和订单/金额检查通过的服务端回调会入账。未启用服务时不会创建假支付订单。微信支付尚未接入。

### 阿里云 OSS

设置 `OSS_ENABLED=true`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET`、`OSS_ENDPOINT`。端点需使用对应区域的 HTTPS 地址，例如 `https://oss-cn-hangzhou.aliyuncs.com`。

头像接口返回对象访问 URL，实际能否公开访问由存储桶策略决定。本仓库不会修改云账号或存储桶权限。

## 本地开发和测试端口

仅需要宿主机开发访问时使用覆盖文件：

```sh
docker compose -f compose.yml -f compose.dev.yml up -d --wait mysql redis
```

映射仅绑定回环地址：MySQL 3307、Redis 6380、后端 8080。正常对外部署使用单独 `compose.yml`。

Windows 完整验证：

```powershell
$env:JAVA_HOME = '你的JDK17目录'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\scripts\test.ps1
```

集成测试以 `it` 前缀创建独立记录并清理；迁移测试创建随机 `parking_migration_` 临时数据库，完成后仅删除这些临时库。测试不会调用真实第三方服务。

## 从旧版本升级

1. 备份旧数据库，并保留旧应用版本和旧配置。
2. 停止全部旧后端实例，避免旧扫描任务或旧写入流程继续改变车位。
3. 在数据库副本验证迁移，再对目标数据库执行 `database/migrations/002-reservation-queue.sql`。
4. 查询 `migration_report`。存在无法唯一匹配的有效记录时，迁移会中止；必须人工核查，不得直接删除占用或随意指定车位。
5. 已完成预约队列迁移的版本继续执行 `database/migrations/003-performance-indexes.sql`；索引迁移可重复执行，不修改业务记录。
6. 运行 `python scripts/init-env.py` 补充缓存密码，然后启动新版本；检查健康、队列恢复日志和余位。

不要对已有库运行 `database/init/001-schema.sql`。初始化目录只用于空数据卷首次启动。迁移脚本中的 `30 MINUTE` 是旧预约的原业务时限，不随新预约时限配置改变。

Windows 导入已核对的迁移文件：

```powershell
Get-Content -Raw -Encoding UTF8 database/migrations/002-reservation-queue.sql |
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 parking_springboot'
```

Linux：

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 parking_springboot' < database/migrations/002-reservation-queue.sql
```

MySQL DDL 自动提交，因此中途发现问题时，已执行的结构调整可能已提交。脚本支持修复数据后重跑；恢复整个旧版本时使用升级前备份。

## 备份和恢复

先创建已忽略的 `backups` 目录，停止后端写入，再备份 MySQL 和 Redis，可获得同一维护窗口内的状态。

```sh
docker compose stop backend frontend
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers parking_springboot' > backups/parking-backup.sql
docker compose exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli SAVE'
```

在 Linux 下用重定向保存 SQL 文件；Windows PowerShell 建议使用 `cmd /c` 处理数据库命令输出重定向，或用支持 UTF-8 原始输出的 PowerShell 7，避免 Windows PowerShell 5 的 UTF-16 重定向。备份可能包含个人信息，应放入已忽略的 `backups` 目录，不提交 Git。

恢复 SQL 时停止后端，将备份导入同名目标库。Redis 数据卷应一并备份；如果 Redis 队列丢失，后端启动会根据数据库未完成事件重建预约过期消息。恢复后再启动服务并核对余位。

```sh
docker compose up -d
```

`docker compose down` 保留命名卷。`down -v` 会删除数据库和 Redis 数据，仅用于明确要重置的测试环境，正常升级不要使用。

## 排障

| 现象 | 检查内容 |
| --- | --- |
| 找不到 Docker Linux engine | Docker Desktop 是否启动，是否启用 Linux 容器及 WSL2 |
| Maven 提示 Java 版本问题 | `java -version` 与 `mvnw -version` 都应使用 JDK 17 |
| Maven 下载失败 | HTTPS Central 的 DNS/网络；使用项目 Wrapper，不启用旧 HTTP 镜像 |
| 前端 API 404/跨域 | Docker 使用 `/api` 同源代理，开发使用 Vite 代理 |
| MySQL 健康失败 | 初始化日志、密码与已有卷内账号是否一致 |
| Redis 认证失败 | 后端与 Redis 的 `REDIS_PASSWORD` 是否相同 |
| 预约未释放 | 后端是否运行、Redis 健康、`reservation_event.last_error` 和迁移报告 |
| 502 | `docker compose ps` 和后端健康检查；启动后等待 Java 初始化完成 |
| 刷新子路由异常 | 使用仓库 Nginx 配置，保留 `try_files` 回退 |

排查队列时查看事件 ID、重试次数与异常类型即可，不需要打印密钥、JWT、支付私钥或完整第三方请求。

## 本轮优化升级和回退

由上一版队列服务升级时，只需备份、执行 `003-performance-indexes.sql`，补充缓存密码并重新构建启动。新环境初始化已包含这些索引，无需再跑增量迁移。

Windows PowerShell 7：

```powershell
python scripts/init-env.py
docker compose stop backend frontend
Get-Content -Raw -Encoding UTF8 database/migrations/003-performance-indexes.sql |
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 parking_springboot'
docker compose up -d --build --wait
```

Linux：

```sh
python3 scripts/init-env.py
docker compose stop backend frontend
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 parking_springboot' < database/migrations/003-performance-indexes.sql
docker compose up -d --build --wait
```

升级前可使用 `docker image tag parking-backend parking-backend:before-upgrade` 和对应前端命令保留镜像。回退时将保留的镜像重新标记为当前服务镜像，运行 `docker compose up -d --no-build`。索引本身兼容上一版，无须删索引或恢复旧数据；若还涉及更早的结构迁移，使用升级前备份恢复并核对记录。

缓存 Redis 可独立停止或重启，后端会回源并记录告警；Token/队列 Redis 故障时登录校验返回 503，前端不会因此清除会话。不要将状态 Redis 改为 LFU/LRU 淘汰，否则可能淘汰 Token 或队列。

`compose.dev.yml` 的回环端口为 MySQL 3307、状态 Redis 6380、缓存 Redis 6381、后端 8080。`compose.test.yml` 使用 23307/26380/26381/28080/28088，配合项目名 `parking-verification` 隔离数据卷；性能测试使用 `parking-benchmark` 项目及 13307/16380/16381/18080/18088。

验证栈使用 Compose 2.24.4 以上的 `!override` 端口语法。正常结束可运行 `docker compose -p parking-verification -f compose.yml -f compose.test.yml down`，保留其数据卷，不影响默认项目。

`/api/actuator/metrics` 仅超级管理员可访问，包含 HTTP 耗时、Hikari 连接池、缓存命中/失败和预约待处理/重试指标。待处理 SQL 指标仅在读取指标时聚合，不用于周期扫描预约或触发过期处理。日志和请求头的 `X-Request-ID` 用于排障，不记录请求 Token 和密钥。

默认容器内存上限：MySQL 2 GiB、后端 2 GiB、状态 Redis 1.5 GiB、缓存 Redis 512 MiB、Nginx 256 MiB；对应 `.env.example` 中的 `*_MEMORY_LIMIT` / `*_LIMIT` 可调整。后端默认 `MaxRAMPercentage=50`，在 2 GiB 容器中允许约 1 GiB 堆，保留本地内存空间。Redis 的容器上限高于 `maxmemory`，为协议缓冲和 AOF 重写留出余量。性能测试另行固定 JVM 1 GiB 和 MySQL 缓冲池 1 GiB，不把空载资源值当作容量上限。
