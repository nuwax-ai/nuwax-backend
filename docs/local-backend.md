# Local Backend Setup

本文档用于在本机通过 OrbStack 启动 nuwax-backend 依赖服务，并用 `local` profile 启动后端。

## 前置条件

- OrbStack 已启动，且 `docker` / `docker compose` 命令可用。
- JDK 17 可用。如果本机没有 Java 运行时，先执行 `brew install openjdk@17`。`scripts/local-backend.sh` 会优先使用 Homebrew 的 JDK 17 路径。
- Maven 可用。当前仓库没有 Maven Wrapper，如果本机没有 `mvn`，先执行 `brew install maven`。

## 启动依赖服务

```bash
cd /Users/apple/workspace/nuwax-backend
./scripts/local-services.sh up
```

这会启动：

| 服务 | 本机地址 | 用途 |
| --- | --- | --- |
| MySQL | `127.0.0.1:13306` | 主业务库，库名 `agent_platform` |
| Redis | `127.0.0.1:16379` | 缓存、任务队列 |
| Milvus | `127.0.0.1:19530` | 知识库向量存储 |
| Elasticsearch | `127.0.0.1:19200` | 日志检索 |
| MinIO | `127.0.0.1:19000` / `127.0.0.1:19001` | Milvus 对象存储依赖 |

MySQL 首次创建数据卷时会自动导入 `sql/init.sql`。最新 `init.sql` 已包含当前增量结构；全新本地库不要再额外执行 `sql/update-*.sql`，否则会出现重复建表或重复字段。

查看服务状态：

```bash
./scripts/local-services.sh ps
```

查看日志：

```bash
./scripts/local-services.sh logs mysql
```

## 启动后端

```bash
cd /Users/apple/workspace/nuwax-backend
./scripts/local-backend.sh
```

等启动完成后检查：

```bash
curl http://127.0.0.1:8081/health
curl http://127.0.0.1:8081/ready
```

Swagger / Knife4j：

```text
http://127.0.0.1:8081/doc.html
```

默认登录账号来自 `sql/init.sql`：

```text
admin@nuwax.com / 123456
```

## 前端连接本地后端

前端项目在 `/Users/apple/workspace/nuwax`。本地联调时，把开发环境的 API 地址改为：

```text
http://127.0.0.1:8081
```

当前前端开发配置文件是 `config/config.development.ts`，原值指向测试环境 `https://testagent.xspaceagi.com`。

## 常用维护命令

停止服务但保留数据：

```bash
./scripts/local-services.sh down
```

彻底重置本地库和依赖数据：

```bash
docker compose -f docker-compose.local.yml down -v
```

上面的 `down -v` 会删除 MySQL、Redis、Milvus、Elasticsearch 等本地数据卷，下次 `up` 会重新导入 `sql/init.sql`。

## 本地配置说明

后端本地配置在：

```text
app-platform-bootstrap/app-platform-web-bootstrap/src/main/resources/application-local.yml
```

它默认使用 OrbStack 暴露到宿主机的端口：

- `DB_PORT=13306`
- `REDIS_PORT=16379`
- `MILVUS_URI=http://127.0.0.1:19530`
- `ES_URL=http://127.0.0.1:19200`

MCP proxy、代码执行、页面构建、Docker proxy、AI agent 等外围能力默认只配置为本地地址，其中 Docker proxy 已默认关闭。需要测试对应功能时，再单独启动这些服务并覆盖相应环境变量。
