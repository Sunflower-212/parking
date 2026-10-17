# 停车场管理系统

基于 Spring Boot 3、Vue 3、MySQL 和 Redis 的停车管理系统，支持用户预约、车位地图、车辆出入场、计费、充值记录及分角色管理。

预约默认保留 **30 分钟**。过期处理采用 **Redisson 持久化延迟队列**，结合数据库事务事件表、原子消息认领与幂等状态更新；不定时扫描预约表。

## 功能与技术栈

| 模块 | 内容 |
| --- | --- |
| 用户端 | 注册登录、个人资料、车位预约与取消、出入场/订单/充值历史、会员信息 |
| 管理端 | 车位地图、停车场、用户、计费规则、出入场及统计 |
| 超级管理员 | 管理员账号、所有停车场及共享计费规则维护 |
| 后端 | Java 17、Spring Boot 3.4.2、MyBatis、PageHelper 2、Redisson 3.43 |
| 前端 | Vue 3、Vite、Pinia、Element Plus、ECharts |
| 基础设施 | MySQL 8.4.4、Redis 7.4.2、Nginx、Docker Compose |
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

停止服务保留数据卷。已有数据库请按部署手册先备份、迁移，不能用全新初始化脚本覆盖旧库。

## 本地开发

需要 JDK 17、Node.js 22/24、Python 3、Docker Compose。Maven Wrapper 固定 Maven 3.9.11；项目使用 HTTPS Maven Central，不依赖全局镜像设置。

Windows 示例：

```powershell
python scripts/init-env.py
docker compose -f compose.yml -f compose.dev.yml up -d --wait mysql redis
# 使用你实际安装的 JDK 17；仅影响当前终端
$env:JAVA_HOME = 'D:\Program Files\Java\jdk17.0.20'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
$env:SPRING_CONFIG_NAME = 'application-example'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/parking_springboot?serverTimezone=Asia/Shanghai'
$env:REDIS_PORT = '6380'
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
.\scripts\test.ps1
```

该脚本启动独立容器基础设施，运行后端测试、迁移测试以及前端测试和构建。运行前设置当前终端的 `JAVA_HOME` 为 JDK 17。

后端普通 `mvnw verify` 执行单元测试；真实 MySQL/Redis 集成测试需设置 `PARKING_INTEGRATION_TESTS=true`、`TEST_DB_URL`、`TEST_REDIS_PORT` 及本地数据库/Redis凭据。CI 已配置完整验证。

## 文档

- [阅读手册](docs/reading-guide.md)：结构、数据关系、权限和预约队列原理。
- [操作手册](docs/user-guide.md)：用户、管理员及超级管理员的操作步骤。
- [Docker 部署手册](docs/docker-deployment.md)：Windows/Linux 部署、配置、迁移和备份恢复。
- [修复与验证记录](docs/verification.md)：修复内容、测试结果及验证边界。

## 配置与隐私

实际 `application.yml`、`.env`、DeepSeek 密钥文件、IDE 数据源配置、压缩包、日志和构建产物均被排除。仓库提供 `.env.example`、后端 `application-example.yml` 和 `parking/deepseek-api_key.example.txt`。

Docker 构建同样排除真实配置，运行时注入环境变量。请在本地保存真实配置；公开仓库和镜像构建上下文不需要这些文件。
