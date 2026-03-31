# Nuwax Backend

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**企业级 AI 智能体开发与运行平台** - 提供完整的智能体创建分发、知识库管理、模型代理、记忆系统和插件生态解决方案。

[English](README.md) | 简体中文

---

## 目录

- [核心特性](#核心特性)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [配置说明](#配置说明) ⚠️ **重要**
- [模块说明](#模块说明)
- [开发指南](#开发指南)
- [贡献指南](#贡献指南)
- [开源协议](#开源协议)
- [商业授权](#商业授权)

---

## 核心特性

### 智能体引擎
- **可视化编排**: 拖拽式工作流设计，零代码创建智能体
- **代码化开发**: 支持 Java/Python 插件开发，灵活扩展
- **多模型支持**: OpenAI、Claude、Ollama、通义千问等主流模型
- **流式输出**: 支持 SSE 实时响应，流畅的用户体验
- **多轮对话**: 完整的会话上下文管理

### 知识库管理
- **文档解析**: PDF、Word、Excel、Markdown 等多种格式
- **向量化存储**: Milvus 向量数据库，高效相似度检索
- **智能分块**: 自动文档切分与重叠处理
- **混合检索**: 向量检索 + 全文检索 + QA 检索

### 记忆系统
- **长期记忆**: 持久化用户数据与偏好
- **短期记忆**: 会话上下文管理
- **分类体系**: 12 种主分类、50+ 种子分类
- **敏感信息过滤**: 自动脱敏处理

### 模型代理（完善中）
- **统一接口**: 屏蔽不同模型 API 差异
- **智能路由**: 根据场景选择最优模型
- **配额管理**: Token 计费和限流控制
- **容错切换**: 自动故障转移

### 插件生态
- **插件市场**: 发布和发现插件
- **MCP 协议**: Model Context Protocol 支持
- **页面应用**: 无代码页面构建

---

## 技术架构

### 技术栈

| 技术类别 | 技术选型 | 版本 |
|---------|---------|------|
| 开发语言 | Java | 17 |
| 框架 | Spring Boot | 3.3.8 |
| 数据库 | MySQL | 8.0+ |
| 向量数据库 | Milvus | 2.5.4 |
| 缓存 | Redis | 7.0+ |
| ORM | MyBatis Plus | 3.5+ |
| API 文档 | Knife4j | 4.0+ |
| 容器化 | Docker | - |
| 编排 | Kubernetes | - |

### 架构图

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           前端层 (Frontend Layer)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────────────┐  │
│  │   PC端    │  │   H5     │  │  小程序   │  │  IM (飞书/钉钉/企微)    │  │
│  └──────────┘  └──────────┘  └──────────┘  └─────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        接入层 (Access Layer)                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────────┐   │
│  │  REST API    │  │  长连接        │  │       WebSocket             │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        应用层 (Application Layer)                         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    组件库 (Component Library)                       │  │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ │  │
│  │  │ 模型  │ │知识库 │ │数据表 │ │ 插件  │ │工作流 │ │  MCP │ │ 技能  │ │  │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    管理端 (Management Portal)                        │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────┐   │  │
│  │  │ 用户管理 │ │ 审核管理 │ │公共模型  │ │ 内容管理 │ │任务管理│   │  │
│  │  ├──────────┤ ├──────────┤ ├──────────┤ ├──────────┤ ├──────┤   │  │
│  │  │ 日志查询 │ │ 菜单权限 │ │ 系统配置 │ │          │ │      │   │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────┘   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    产品应用 (Product Applications)                    │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │  │
│  │  │   网页应用     │  │ 问答型智能体  │  │    通用型智能体          │  │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                      基础设施层 (Infrastructure Layer)                      │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                      下层组件 (Lower-level Components)                │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────────┐ │  │
│  │  │  云端沙箱    │  │ nuwaclaw    │  │    通用智能体引擎            │ │  │
│  │  │             │  │ 个人电脑     │  │                             │  │
│  │  │             │  │ 客户端       │  │  ┌───────────────────────┐ │  │
│  │  │             │  │ (mac/win/    │  │  │  MCP 集成             │ │  │
│  │  │             │  │  docker)     │  │  ├───────────────────────┤ │  │
│  │  │             │  │             │  │  │  文件管理             │ │  │
│  │  │             │  │             │  │  │  SKILL 管理           │ │  │
│  │  │             │  │             │  │  │  ACP 适配层           │ │  │
│  │  │             │  │             │  │  │  ┌─────────────────┐ │ │  │
│  │  │             │  │             │  │  │  │ 支持 Agent:    │ │ │  │
│  │  │             │  │             │  │  │  │ claudecode      │ │ │  │
│  │  │             │  │             │  │  │  │ opencode        │ │ │  │
│  │  │             │  │             │  │  │  │ codex           │ │ │  │
│  │  │             │  │             │  │  │  │ openclaw        │ │ │  │
│  │  │             │  │             │  │  │  │ kimicli         │ │ │  │
│  │  │             │  │             │  │  │  └─────────────────┘ │ │  │
│  │  │             │  │             │  │  ├───────────────────────┤ │  │
│  │  │             │  │             │  │  │  浏览器操作           │ │  │
│  │  │             │  │             │  │  │  GUI 操作             │ │  │
│  │  │             │  │             │  │  │  网络通道             │ │  │
│  │  │             │  │             │  │  │  基础运行环境集成      │ │  │
│  │  │             │  │             │  │  └───────────────────────┘ │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────────┘ │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    核心基础设施 (Core Infrastructure)                │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────┐  │  │
│  │  │ Database │ │  Cache   │ │  Vector  │ │  Search  │ │ Model │  │  │
│  │  │  MySQL   │ │  Redis   │ │  Milvus  │ │ Elastic  │ │ Proxy│  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### 项目仓库纵览

Nuwax AI 智能体平台由多个相互关联的仓库组成：

#### **前端与移动端**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **nuwax** | 前端 Web | [https://github.com/nuwax-ai/nuwax](https://github.com/nuwax-ai/nuwax) |
| **nuwax-mobile** | 移动端应用 | [https://github.com/nuwax-ai/nuwax-mobile](https://github.com/nuwax-ai/nuwax-mobile) |
| **noVNC** | 基于网页的 VNC 客户端 | [https://github.com/nuwax-ai/noVNC](https://github.com/nuwax-ai/noVNC) |

#### **后端与应用层**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **nuwax-backend** | 应用层（后端）- **当前仓库** | [https://github.com/nuwax-ai/nuwax-backend](https://github.com/nuwax-ai/nuwax-backend) |

#### **智能体引擎与客户端**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **nuwaclaw** | 智能体电脑客户端（mac/win/docker）| [https://github.com/nuwax-ai/nuwaclaw](https://github.com/nuwax-ai/nuwaclaw) |
| **nuwaxcode** | 女娲智能体引擎（基于开源 opencode）| [https://github.com/nuwax-ai/nuwaxcode](https://github.com/nuwax-ai/nuwaxcode) |
| **claude-code-acp-ts** | 基于 Zed 的 Claude Code ACP | [https://github.com/nuwax-ai/claude-code-acp-ts](https://github.com/nuwax-ai/claude-code-acp-ts) |

#### **基础设施与服务**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **rcoder** | 沙箱与容器调度（含通用智能体引擎）| [https://github.com/nuwax-ai/rcoder](https://github.com/nuwax-ai/rcoder) |
| **mcp-proxy** | MCP 服务（nuwaclaw 和沙箱均使用）| [https://github.com/nuwax-ai/mcp-proxy](https://github.com/nuwax-ai/mcp-proxy) |
| **nuwax-file-server** | 文件服务（沙箱和 nuwaclaw 使用，含 skill 同步）| [https://github.com/nuwax-ai/nuwax-file-server](https://github.com/nuwax-ai/nuwax-file-server) |

#### **网页应用开发**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **xagi-frontend-templates** | 网页应用开发模版 | [https://github.com/nuwax-ai/xagi-frontend-templates](https://github.com/nuwax-ai/xagi-frontend-templates) |
| **vite-plugin-design-mode** | 可视化编辑 Vite 插件 | [https://github.com/nuwax-ai/vite-plugin-design-mode](https://github.com/nuwax-ai/vite-plugin-design-mode) |
| **dev-inject** | 网页应用智能脚本注入 | [https://github.com/nuwax-ai/dev-inject](https://github.com/nuwax-ai/dev-inject) |

#### **插件与脚本执行**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **run_code_rmcp** | 插件脚本执行（TS/JS/Python）| [https://github.com/nuwax-ai/run_code_rmcp](https://github.com/nuwax-ai/run_code_rmcp) |

#### **网络与工具**
| 仓库名称 | 描述 | 地址 |
|---------|------|------|
| **lanproxy-go-client** | 网络穿透客户端（nuwaclaw 使用）| [https://github.com/ffay/lanproxy-go-client](https://github.com/ffay/lanproxy-go-client) |

---

### 设计模式

- **DDD 领域驱动设计**: 清晰的层次划分
- **CQRS**: 命令查询职责分离
- **策略模式**: 模型选择、插件调用
- **观察者模式**: 消息推送
- **仓储模式**: 数据访问抽象

---

## 快速开始

### 环境要求

| 组件     | 最低要求   | 推荐配置       |
|--------|--------|------------|
| Java   | JDK 17 | OpenJDK 17 |
| MySQL  | 8.0+   | 8.0+       |
| Redis  | 6.0+   | 7.0+       |
| Milvus | 2.5+   | 2.5+       |
| Elacticsearch  | 9.2.1  | 9.2.1      |
| 内存     | 4GB    | 8GB+       |
| CPU    | 2 核    | 4 核+       |

### 1. 克隆项目

```bash
git clone https://github.com/nuwax-ai/nuwax-backend.git
cd nuwax-backend
```

### 2. 启动基础服务

- Milvus 向量数据库 https://github.com/milvus-io/milvus
- 安装配置mysql，创建数据库 `agent_platform`，导入 sql/init.sql
- 安装配置 Elasticsearch 9.2.1，中文环境需要配置中文分词插件
- mcp-proxy项目部署 https://github.com/nuwax-ai/mcp-proxy
- 网页开发调度以及智能体沙箱服务 https://github.com/nuwax-ai/rcoder

> 开发测试时可以将官方提供的部署安装包跑起来后直接使用各项基础服务

### 3. 增加配置文件

将 `application-${env}.sample.yml` 文件复制为 `application-${env}.yml` 文件：
 

### 4. 启动应用

**方式 1: 使用 IDEA 等 IDE 启动**
- 打开 `app-platform-bootstrap/app-platform-web-bootstrap/src/main/java/com/xspaceagi/PlatformApiApplication.java`
- 点击运行按钮启动

**方式 2: 使用 Maven 命令**
```bash
# 编译项目
mvn clean package -DskipTests

# 启动应用
cd app-platform-bootstrap/app-platform-web-bootstrap
java -jar target/app-platform-web-bootstrap-*.jar

# 或使用 Maven 直接运行
mvn spring-boot:run -pl app-platform-bootstrap/app-platform-web-bootstrap -Pdev
```

**方式 3: 使用 IDEA Maven 面板**
- 打开 Maven 面板
- 找到 `app-platform-web-bootstrap` 模块
- 右键选择 `Run` -> `spring-boot:run`

### 5. 访问应用

应用启动后，访问（默认端口 8081）：

- **API 文档**: http://localhost:8081/doc.html
- **健康检查**: http://localhost:8081/health

---

## 模块说明

### 项目结构

```
agent-platform/
├── app-platform-bootstrap/          # 启动模块
│   └── app-platform-web-bootstrap/  # Web 应用启动入口
├── app-platform-foundation/         # 基础模块
│   ├── system-spec/                 # 系统规范定义
│   ├── system-sdk/                  # 系统 SDK
│   └── system-domain/               # 系统领域模型
├── app-platform-modules/            # 业务模块
│   ├── app-platform-agent/          # 智能体核心模块
│   ├── app-platform-knowledge/      # 知识库模块
│   ├── app-platform-memory/         # 记忆模块
│   ├── app-platform-model-proxy/    # 模型代理模块
│   ├── app-platform-mcp/            # MCP 协议模块
│   ├── app-platform-compose/        # 自定义表格模块
│   ├── app-platform-custom-page/    # 自定义页面模块
│   ├── app-platform-eco-market/     # 生态市场模块
│   ├── app-platform-sandbox/        # 沙箱模块
│   ├── app-platform-log/            # 日志模块
│   └── platform-system/             # 平台系统模块
├── fast-boot-dependencies/          # 依赖管理
└── specs/                           # 规格文档
```

### 核心模块

| 模块 | 说明                        |
|-----|---------------------------|
| **Agent** | 智能体创建、会话管理、消息处理           |
| **Knowledge** | 知识库管理、文档上传、向量检索           |
| **Memory** | 长期记忆存储与检索                 |
| **Model Proxy** | 模型代理调用接口                  |
| **MCP** | Model Context Protocol 实现 |
| **Eco Market** | 插件市场与模板管理                 |
| **Compose** | 自定义表格功能                   |
| **Custom Page** | 无代码页面构建                   |

---

## 配置说明

### 配置文件结构

```
app-platform-web-bootstrap/src/main/resources/
├── application.yml                 # 主配置文件（公共配置）
├── application-dev.yml            # 开发环境配置（本地使用，不提交）
├── application-test.yml           # 测试环境配置（可选）
└── application-prod.yml           # 生产环境配置（可选）
```

### 环境变量完整列表

#### 数据库配置

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|-----|--------|
| `DB_HOST` | MySQL 数据库地址 | ✅ | localhost |
| `DB_PORT` | MySQL 端口 | ✅ | 3306 |
| `DB_NAME` | 数据库名称 | ✅ | agent_platform |
| `DB_USERNAME` | 数据库用户名 | ✅ | root |
| `DB_PASSWORD` | 数据库密码 | ✅ | - |

#### 数据表组件依赖的数据库（Doris库未测试，支持mysql）

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|--|--------|
| `DORIS_HOST` | Doris 地址 | ✅ | localhost |
| `DORIS_DB_NAME` | Doris 数据库名 | ✅ | agent_custom_table |
| `DORIS_USERNAME` | Doris 用户名 | ✅ | root |
| `DORIS_PASSWORD` | Doris 密码 | ✅ | - |

#### Redis 配置

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|----|--------|
| `REDIS_HOST` | Redis 地址 | ✅ | localhost |
| `REDIS_PORT` | Redis 端口 | ✅ | 6379 |
| `REDIS_PASSWORD` | Redis 密码 | ✅ | - |
| `REDIS_DB` | Redis 数据库编号 | ✅ | 1 |

#### Milvus 向量数据库

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|-----|--------|
| `MILVUS_URI` | Milvus 连接地址 | ✅ | http://localhost:19530 |
| `MILVUS_USER` | Milvus 用户名 | ✅ | root |
| `MILVUS_PASSWORD` | Milvus 密码 | ✅ | - |

#### Elasticsearch（可选）

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|--|--------|
| `ES_URL` | Elasticsearch 地址 | ✅ | http://localhost:9200 |
| `ES_USERNAME` | Elasticsearch 用户名 | ✅ | elastic |
| `ES_PASSWORD` | Elasticsearch 密码 | ✅ | - |
| `ES_API_KEY` | Elasticsearch API Key | ✅ | - |

#### 安全配置

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|------|--------|
| `JWT_SECRET_KEY` | JWT 签名密钥 | ✅ | - (至少32位) |

#### 腾讯云 COS（可选）

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|---|--------|
| `COS_SECRET_ID` | 腾讯云 COS 密钥 ID | ✅ | - |
| `COS_SECRET_KEY` | 腾讯云 COS 密钥 Key | ✅ | - |
| `COS_BASE_URL` | COS 访问域名 | ✅ | https://... |

#### 文件存储

| 变量名                  | 说明                       | 必填 | 默认值                         |
|----------------------|--------------------------|---|-----------------------------|
| `FILE_UPLOAD_FOLDER` | 文件上传目录                   | ✅ | /tmp/uploads                |
| `FILE_BASE_URL`      | 文件访问 URL                 | ✅ | https://yourdomain/api/file |
| `STORAGE_TYPE`       | 存储类型，暂时支持腾讯云cos和本地存储file | ✅ | file                     |

#### 外部服务（可选）

| 变量名 | 说明               | 必填 | 默认值 |
|-------|------------------|---|--------|
| `CODE_EXECUTE_URL` | 代码执行服务           | ✅ | http://localhost:8020/... |
| `LOG_SERVICE_URL` | 日志服务地址，可以不配置，已废弃 | ✅ | http://localhost:8097 |
| `MCP_PROXY_URL` | MCP 代理地址         | ✅ | http://localhost:8020 |

#### 页面应用开发服务（可选）

| 变量名 | 说明 | 必填 | 默认值 |
|-------|------|--|--------|
| `DEV_SERVER_HOST` | 开发服务器地址 | ✅ | http://localhost |
| `PROD_SERVER_HOST` | 生产服务器地址 | ✅ | http://localhost:8099 |
| `BUILD_SERVER_URL` | 构建服务地址 | ✅ | http://localhost:60000/api |
| `AI_AGENT_URL` | AI 智能体地址 | ✅ | http://localhost:8086 |
| `DOCKER_PROXY_URL` | Docker 代理地址 | ✅ | http://localhost:8088 |

#### 内网穿透代理配置（可选）

| 变量名 | 说明        | 必填 | 默认值         |
|-------|-----------|------|-------------|
| `SERVICE_HOST` | 内部服务地址    | ❌ | 127.0.0.1   |
| `BIND_HOST` | 绑定地址      | ❌ | 0.0.0.0     |
| `REVERSE_PORTS` | 端口范围      | ❌ | 30000-40000 |
| `OUTER_HOST` | 客户端连接IP地址 | ❌ | -           |
| `OUTER_PORT` | 客户端连接端口   | ❌ | 6443        |

#### 模型代理（可选）

| 变量名 | 说明                                | 必填 | 默认值 |
|-------|-----------------------------------|------|--------|
| `MODEL_API_BASE_URL` | 模型 API 地址，对应可访问MODEL_PROXY_PORT端口 | ❌ | - |
| `MODEL_PROXY_ENABLE` | 是否启用代理                            | ❌ | true |
| `MODEL_PROXY_PORT` | 代理端口                              | ❌ | 18086 |
| `MODEL_PROXY_SAVE_LOG` | 是否保存日志，依赖elasticsearch            | ❌ | true |


---

## 开发指南

### 代码规范

项目遵循 DDD 分层架构：

```
UI 层 → Adapter 层 → Application 层 → Domain 层 → Infrastructure 层
```

---

## 贡献指南

欢迎贡献代码、文档、Bug 报告！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

### 贡献者

感谢所有为这个项目做出贡献的开发者！

---

## 开源协议

本项目采用 **Apache License 2.0** 开源协议。

详见 [LICENSE](LICENSE) 文件。

---

## 商业授权

### 开源使用（免费）

本软件可以免费使用、修改和分发，但需要：

- ✅ 保留原始版权声明
- ✅ 包含 Apache 2.0 许可证文本
- ✅ 说明你的产品基于本软件

### 商业授权（付费）

如果你需要：

- 🏢 **移除版权声明**（白标产品）
- 🔄 **OEM 合作**（集成到你的产品中销售）
- 🎨 **自定义品牌**（使用你自己的品牌）
- 🚀 **优先支持**（企业级技术支持）

可以申请商业授权，获取额外权利。

详细信息请查看：[COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md)
