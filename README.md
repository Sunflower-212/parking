# 停车场管理系统

基于 Spring Boot 3、Vue 3、MySQL 和 Redis 的停车管理系统，支持用户预约、车位地图、车辆出入场、计费、充值记录及分角色管理。

预约默认保留 **30 分钟**。过期处理采用 **Redisson 持久化延迟队列**，结合数据库事务事件表、原子消息认领与幂等状态更新；不定时扫描预约表。

充值单默认保留 **30 分钟**（`PAYMENT_TIMEOUT`），通过独立 Redisson 支付队列核验到账和到期关单。支付宝通知与主动查单共用幂等入账事务；浏览器返回成功不能作为到账依据。支付在当前标签页完成，结果页确认入账后刷新账户。道路、墙体会根据四向邻居自动拼接，HTTP 访问也支持地图批量生成。

## 功能与技术栈

| 模块 | 内容 |
| --- | --- |
| 用户端 | 注册登录、个人资料、车位预约与取消、出入场/订单/充值历史、会员信息 |
| 停车场管理员 | 所属停车场、车位地图、出入场、停车订单及运营统计 |
| 超级管理员 | 平台用户、充值流水、管理员账号、所有停车场及共享规则维护 |
| 后端 | Java 17、Spring Boot 3.4.2、MyBatis、PageHelper 2、Redisson 3.43 |
| 可选后台任务 | RabbitMQ 4.1.4、事务 Outbox、确认投递、手动 ACK、死信重放、异步用户导出与管理审计 |
| 前端 | Vue 3、Vite、Pinia、Element Plus、ECharts |
| 基础设施 | MySQL 8.4.4、Redis 7.4.2、Caffeine 3.1.8、Nginx、Docker Compose |
| 可选集成 | DeepSeek、百度车牌识别、支付宝沙箱、阿里云 OSS |

外部服务默认关闭，不需要第三方密钥即可启动核心系统。图片识别出入场、真实充值、云头像上传等功能需要启用对应服务。微信支付尚未接入，不提供模拟到账。

## Docker 快速启动

安装并启动 Docker Desktop（Linux 容器模式），然后在仓库根目录运行：

```sh
python scripts/init-env.py
docker compose up -d --build --wait
```

打开 **http://localhost:8088**。首次超级管理员的用户名、密码位于本地 `.env` 的 `BOOTSTRAP_ADMIN_USERNAME`、`BOOTSTRAP_ADMIN_PASSWORD`。初始化脚本会生成随机密码，重复运行保留已有配置。

首次运行后，先创建计费规则、停车场和车位地图，再注册用户、完善车牌信息进行预约。MySQL 和 Redis 默认不对宿主机开放端口。

```sh
docker compose ps
docker compose logs --tail=100 backend
docker compose down
```

停止服务保留数据卷。已有数据库请先备份，检查 `database/migrations` 下的增量脚本，不能用全新初始化脚本覆盖旧库。容量版本升级必须停止所有后端写入，再执行 `python scripts/migrate-capacity.py --project parking --buckets 16`，核对后以 `CAPACITY_BUCKETS=16` 启动；回退使用同脚本的 `--rollback`，不能只切换旧镜像。

支付版本升级前必须停止旧后端，备份数据库，再执行 `database/migrations/008-payment-lifecycle.sql`，避免旧充值扫描与新队列同时工作。迁移保留历史商户单号和余额，未确认的历史取消单需要核验，不能直接补余额。已有新商户单号或待核验交易时，不直接降级旧后端。

## 阿里云部署：全部服务与 /parking/

开发环境使用完整的 `compose.dev.yml`，隔离验收使用 `compose.test.yml`，云端使用 `compose.cloud.yml`；每次业务启动只选择一份文件，不叠加多个环境。公共 Nginx 入口保持独立，避免停车系统停止时影响同机其他应用。历史专项压测配置单独保留。

云端启用 DeepSeek、百度 OCR、支付宝沙箱、OSS 和 RabbitMQ。镜像不包含密钥，先生成服务器自己的环境文件并填齐凭据：

```sh
python3 scripts/init-env.py --cloud
# 在服务器填写 .env 中的第三方凭据，先更换曾公开展示的支付宝私钥。
python3 scripts/check-cloud-config.py
docker network create web-entry
sh scripts/start-cloud.sh
docker compose -p web-entry -f deploy/gateway/compose.yml up -d --pull never
```

共享网络已存在时复用，不要删除重建。离线包须先 `docker load`；启动脚本只使用本地镜像。访问 `http://服务器IP/parking`；HTTPS 需另行配置有效域名和证书，443 端口映射本身不代表 TLS 已启用。

支付宝的 `ALIPAY_NOTIFY_URL` 使用公开的 `/parking/api/user/rechargeNotify`，`ALIPAY_RETURN_URL` 使用 `/parking/user/paymentResult`；旧账户返回路径仍兼容。真实查单、通知和云端到账需单独联调，隔离测试结果不代表某笔实际付款已入账。

2 核 2GB 配置为低负载起点：MySQL 512MiB、后端 640MiB（最大堆 320MiB）、RabbitMQ 320MiB（告警水位 192MiB）、两个 Redis 分别 128/64MiB、前端和入口各 64MiB。容器上限合计 1792MiB，不能代表整机峰值；建议 2GiB Swap 缓冲及至少 4GB 内存用于长期使用。CentOS 7 已停止维护，目标机兼容性、外网回调和真实服务授权仍需实机验收。

RabbitMQ 管理页面仅绑定回环地址，通过 SSH 隧道访问。数据库、Redis、后端端口不向公网开放。已有安装沿用项目名 parking、数据卷和 .env，升级前备份；不使用 down -v。全部第三方开关由云端配置明确启用，配置校验只证明格式，不证明密钥有效、已轮换或服务已授权。

完整的 Xshell 8／Xftp 8 手册及实际验证记录仅保存在本地 `docs/全部服务云端部署与验收.md`，docs 不上传仓库或放入镜像。

## 本地开发

需要 JDK 17、Node.js 22/24、Python 3、Docker Compose。Maven Wrapper 固定 Maven 3.9.11；项目使用 HTTPS Maven Central，不依赖全局镜像设置。

Windows 示例：

```powershell
python scripts/init-env.py
docker compose -p parking -f compose.dev.yml up -d --wait mysql redis redis-cache
# 使用你实际安装的 JDK 17；仅影响当前终端
$env:JAVA_HOME = 'D:\Program Files\Java\jdk17.0.20'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
$env:SPRING_CONFIG_NAME = 'application-example'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/parking_springboot?serverTimezone=Asia/Shanghai'
$env:REDIS_PORT = '6380'
$env:CACHE_ENABLED = 'true'
$env:CACHE_LOCAL_ENABLED = 'true'
$env:CACHE_REDIS_PORT = '6381'
cd parking/parking
.\mvnw.cmd spring-boot:run
```

在另一终端启动前端：

```sh
cd vue3/parking-vue
npm ci
npm run dev
```

访问 http://localhost:5173，Vite 将 `/api` 代理到后端 8080。自定义后端地址可设置 `VITE_API_BASE_URL`，此变量只能包含公开地址，不能包含密钥。

## 验证

Windows 下完整验证：

```powershell
.\scripts\test.ps1 -IncludeBrowser
# 同时验证 RabbitMQ、容器重启和16桶容量模式
.\scripts\test.ps1 -IncludeBrowser -IncludeBackground -IncludeRecovery -CapacityBuckets 16
```

该脚本启动独立容器基础设施，运行后端测试、迁移测试以及前端测试和构建。运行前设置当前终端的 `JAVA_HOME` 为 JDK 17。

后端普通 `mvnw verify` 执行单元测试；真实 MySQL/Redis 集成测试需设置 `PARKING_INTEGRATION_TESTS=true`、`TEST_DB_URL`、`TEST_REDIS_PORT`、`TEST_CACHE_PORT` 及本地数据库/Redis 凭据。CI 已配置完整验证。

## 本地文档与发布规则

手册、性能报告及面试材料仅在本地 `docs/` 保存，该目录不再纳入 Git 或 Docker 构建上下文。仓库只发布源码、配置示例、部署和验证脚本；README 保留公共启动说明。

提交前运行 `python scripts/check-publication.py` 和 `python scripts/check-secrets.py`。CI 同样检查禁止跟踪本地文档，避免后续误传。

## 配置与隐私

实际 `application.yml`、`.env`、DeepSeek 密钥文件、IDE 数据源配置、压缩包、日志和构建产物均被排除。仓库提供 `.env.example`、后端 `application-example.yml` 和 `parking/deepseek-api_key.example.txt`。

Docker 构建同样排除真实配置，运行时注入环境变量。请在本地保存真实配置；公开仓库和镜像构建上下文不需要这些文件。
