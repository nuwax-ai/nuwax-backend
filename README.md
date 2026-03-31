# Nuwax Backend

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Enterprise-grade AI Agent Development and Operation Platform** - Providing a complete solution for agent creation and distribution, knowledge base management, model proxy, memory system, and plugin ecosystem.

English | [简体中文](README.zh-CN.md)

---

## Table of Contents

- [Core Features](#core-features)
- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration) ⚠️ **Important**
- [Module Description](#module-description)
- [Development Guide](#development-guide)
- [Contributing](#contributing)
- [License](#license)
- [Commercial License](#commercial-license)

---

## Core Features

### Agent Engine
- **Visual Orchestration**: Drag-and-drop workflow design, zero-code agent creation
- **Code-based Development**: Support for Java/Python plugin development, flexible extension
- **Multi-model Support**: OpenAI, Claude, Ollama, Qwen, and other mainstream models
- **Streaming Output**: SSE real-time response, smooth user experience
- **Multi-turn Conversations**: Complete session context management

### Knowledge Base Management
- **Document Parsing**: PDF, Word, Excel, Markdown, and other formats
- **Vector Storage**: Milvus vector database, efficient similarity search
- **Intelligent Chunking**: Automatic document segmentation and overlap processing
- **Hybrid Retrieval**: Vector search + Full-text search + QA search

### Memory System
- **Long-term Memory**: Persistent user data and preferences
- **Short-term Memory**: Session context management
- **Classification System**: 12 main categories, 50+ sub-categories
- **Sensitive Information Filtering**: Automatic desensitization

### Model Proxy (In Development)
- **Unified Interface**: Shield differences between different model APIs
- **Intelligent Routing**: Select the optimal model based on scenarios
- **Quota Management**: Token billing and rate limiting
- **Failover**: Automatic fault tolerance switching

### Plugin Ecosystem
- **Plugin Market**: Publish and discover plugins
- **MCP Protocol**: Model Context Protocol support
- **Page Applications**: No-code page builder

---

## Architecture

### Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Java | 17 |
| Framework | Spring Boot | 3.3.8 |
| Database | MySQL | 8.0+ |
| Vector Database | Milvus | 2.5.4 |
| Cache | Redis | 7.0+ |
| ORM | MyBatis Plus | 3.5+ |
| API Documentation | Knife4j | 4.0+ |
| Container | Docker | - |
| Orchestration | Kubernetes | - |

### Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           Frontend Layer                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────────────┐  │
│  │  PC Web  │  │   H5     │  │ Mini App │  │  IM (Feishu/DingTalk/  │  │
│  │          │  │          │  │          │  │   WeCom/Slack)         │  │
│  └──────────┘  └──────────┘  └──────────┘  └─────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        Access Layer                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────────┐   │
│  │  REST API    │  │  Long        │  │       WebSocket             │   │
│  │              │  │ Connection   │  │                             │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                      Application Layer                                     │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │              Component Library                                       │  │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ │  │
│  │  │ Model│ │Know- │ │ Data │ │Plugin│ │ Work-│ │  MCP │ │ Skill│ │  │
│  │  │      │ │ ledge│ │ Table│ │      │ │ flow │ │      │ │      │ │  │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │              Management Portal                                        │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │  │
│  │  │  User    │ │  Audit   │ │  Public  │ │ Content  │ │   Task   │ │  │
│  │  │Management│ │Management│ │  Model   │ │Management│ │Management│ │  │
│  │  ├──────────┤ ├──────────┤ ├──────────┤ ├──────────┤ ├──────────┤ │  │
│  │  │   Log    │ │   Menu   │ │ System   │ │          │ │          │ │  │
│  │  │  Query   │ │Permission│ │  Config  │ │          │ │          │ │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │              Product Applications                                    │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │  │
│  │  │   Web App    │  │   Q&A        │  │    General Agent         │  │  │
│  │  │              │  │   Agent      │  │                          │  │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                    Infrastructure Layer                                    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    Lower-level Components                            │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────────┐ │  │
│  │  │   Cloud     │  │  nuwaclaw   │  │   General Agent Engine     │ │  │
│  │  │   Sandbox   │  │   PC Client │  │                            │ │  │
│  │  │             │  │ (mac/win/   │  │  ┌───────────────────────┐ │  │
│  │  │             │  │  docker)    │  │  │  MCP Integration      │ │  │
│  │  │             │  │             │  │  ├───────────────────────┤ │  │
│  │  │             │  │             │  │  │  File Management      │ │  │
│  │  │             │  │             │  │  │  Skill Management     │ │  │
│  │  │             │  │             │  │  │  ACP Adapter Layer    │ │  │
│  │  │             │  │             │  │  │  ┌─────────────────┐ │ │  │
│  │  │             │  │             │  │  │  │ Supported Agent:│ │ │  │
│  │  │             │  │             │  │  │  │ claudecode      │ │ │  │
│  │  │             │  │             │  │  │  │ opencode        │ │ │  │
│  │  │             │  │             │  │  │  │ codex           │ │ │  │
│  │  │             │  │             │  │  │  │ openclaw        │ │ │  │
│  │  │             │  │             │  │  │  │ kimicli         │ │ │  │
│  │  │             │  │             │  │  │  └─────────────────┘ │ │  │
│  │  │             │  │             │  │  ├───────────────────────┤ │  │
│  │  │             │  │             │  │  │  Browser             │ │  │
│  │  │             │  │             │  │  │  Automation          │ │  │
│  │  │             │  │             │  │  │  GUI Automation      │ │  │
│  │  │             │  │             │  │  │  Network Channel     │ │  │
│  │  │             │  │             │  │  │  Runtime Integration │ │  │
│  │  │             │  │             │  │  └───────────────────────┘ │  │
│  │  │             │  │             │  └─────────────────────────────┘ │  │
│  │  └─────────────┘  └─────────────┘                                   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    Core Infrastructure                                │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────┐  │  │
│  │  │ Database │ │  Cache   │ │  Vector  │ │  Search  │ │ Model │  │  │
│  │  │  MySQL   │ │  Redis   │ │  Milvus  │ │ Elastic  │ │ Proxy│  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Project Repository Overview

The Nuwax AI Agent Platform consists of multiple interconnected repositories:

#### **Frontend & Mobile**
| Repository | Description | URL |
|-----------|-------------|-----|
| **nuwax** | Frontend Web | [https://github.com/nuwax-ai/nuwax](https://github.com/nuwax-ai/nuwax) |
| **nuwax-mobile** | Mobile Application | [https://github.com/nuwax-ai/nuwax-mobile](https://github.com/nuwax-ai/nuwax-mobile) |
| **noVNC** | Web-based VNC Client | [https://github.com/nuwax-ai/noVNC](https://github.com/nuwax-ai/noVNC) |

#### **Backend & Application Layer**
| Repository | Description | URL |
|-----------|-------------|-----|
| **nuwax-backend** | Application Layer (Backend) - **This Repository** | [https://github.com/nuwax-ai/nuwax-backend](https://github.com/nuwax-ai/nuwax-backend) |

#### **Agent Engine & Clients**
| Repository | Description | URL |
|-----------|-------------|-----|
| **nuwaclaw** | Agent PC Client (mac/win/docker) | [https://github.com/nuwax-ai/nuwaclaw](https://github.com/nuwax-ai/nuwaclaw) |
| **nuwaxcode** | Nuwa Agent Engine (based on open-source opencode) | [https://github.com/nuwax-ai/nuwaxcode](https://github.com/nuwax-ai/nuwaxcode) |
| **claude-code-acp-ts** | Claude Code ACP based on Zed | [https://github.com/nuwax-ai/claude-code-acp-ts](https://github.com/nuwax-ai/claude-code-acp-ts) |

#### **Infrastructure & Services**
| Repository | Description | URL |
|-----------|-------------|-----|
| **rcoder** | Sandbox & Container Scheduling (includes General Agent Engine) | [https://github.com/nuwax-ai/rcoder](https://github.com/nuwax-ai/rcoder) |
| **mcp-proxy** | MCP Service (used by nuwaclaw and sandbox) | [https://github.com/nuwax-ai/mcp-proxy](https://github.com/nuwax-ai/mcp-proxy) |
| **nuwax-file-server** | File Service (used by sandbox and nuwaclaw, includes skill sync) | [https://github.com/nuwax-ai/nuwax-file-server](https://github.com/nuwax-ai/nuwax-file-server) |

#### **Web Application Development**
| Repository | Description | URL |
|-----------|-------------|-----|
| **xagi-frontend-templates** | Web Application Development Templates | [https://github.com/nuwax-ai/xagi-frontend-templates](https://github.com/nuwax-ai/xagi-frontend-templates) |
| **vite-plugin-design-mode** | Visual Editor Vite Plugin | [https://github.com/nuwax-ai/vite-plugin-design-mode](https://github.com/nuwax-ai/vite-plugin-design-mode) |
| **dev-inject** | Web Application Smart Script Injection | [https://github.com/nuwax-ai/dev-inject](https://github.com/nuwax-ai/dev-inject) |

#### **Plugin & Script Execution**
| Repository | Description | URL |
|-----------|-------------|-----|
| **run_code_rmcp** | Plugin Script Execution (TypeScript/JavaScript/Python) | [https://github.com/nuwax-ai/run_code_rmcp](https://github.com/nuwax-ai/run_code_rmcp) |

#### **Network & Utilities**
| Repository | Description | URL |
|-----------|-------------|-----|
| **lanproxy-go-client** | Network Tunnel Client (used by nuwaclaw) | [https://github.com/ffay/lanproxy-go-client](https://github.com/ffay/lanproxy-go-client) |

---

### Design Patterns

- **DDD (Domain-Driven Design)**: Clear layer separation
- **CQRS**: Command Query Responsibility Segregation
- **Strategy Pattern**: Model selection, plugin invocation
- **Observer Pattern**: Message broadcasting
- **Repository Pattern**: Data access abstraction

---

## Quick Start

### Prerequisites

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| Java | JDK 17 | OpenJDK 17 |
| MySQL | 8.0+ | 8.0+ |
| Redis | 6.0+ | 7.0+ |
| Milvus | 2.5+ | 2.5+ |
| Elasticsearch | 9.2.1 | 9.2.1 |
| Memory | 4GB | 8GB+ |
| CPU | 2 cores | 4 cores+ |

### 1. Clone the Repository

```bash
git clone https://github.com/nuwax-ai/nuwax-backend.git
cd nuwax-backend
```

### 2. Start Basic Services

- **Milvus Vector Database**: https://github.com/milvus-io/milvus
- **MySQL**: Install and configure MySQL, create database `agent_platform`, import `sql/init.sql`
- **Elasticsearch**: Install and configure Elasticsearch 9.2.1, configure Chinese word segmentation plugin for Chinese environment
- **mcp-proxy**: https://github.com/nuwax-ai/mcp-proxy
- **Web development scheduling and agent sandbox service**: https://github.com/nuwax-ai/rcoder

> For development and testing, you can use the official deployment package to run all basic services directly.

### 3. Add Configuration File

Copy `application-${env}.sample.yml` to `application-${env}.yml`:

### 4. Start the Application

**Option 1: Using IDE (IntelliJ IDEA, etc.)**
- Open `app-platform-bootstrap/app-platform-web-bootstrap/src/main/java/com/xspaceagi/PlatformApiApplication.java`
- Click the run button to start

**Option 2: Using Maven Commands**
```bash
# Build the project
mvn clean package -DskipTests

# Start the application
cd app-platform-bootstrap/app-platform-web-bootstrap
java -jar target/app-platform-web-bootstrap-*.jar

# Or run directly with Maven
mvn spring-boot:run -pl app-platform-bootstrap/app-platform-web-bootstrap -Pdev
```

**Option 3: Using IDEA Maven Panel**
- Open the Maven panel
- Find the `app-platform-web-bootstrap` module
- Right-click and select `Run` -> `spring-boot:run`

### 5. Access the Application

After starting, access the application (default port 8081):

- **API Documentation**: http://localhost:8081/doc.html
- **Health Check**: http://localhost:8081/health

---

## Module Description

### Project Structure

```
agent-platform/
├── app-platform-bootstrap/          # Bootstrap module
│   └── app-platform-web-bootstrap/  # Web application entry point
├── app-platform-foundation/         # Foundation module
│   ├── system-spec/                 # System specification
│   ├── system-sdk/                  # System SDK
│   └── system-domain/               # System domain models
├── app-platform-modules/            # Business modules
│   ├── app-platform-agent/          # Agent core module
│   ├── app-platform-knowledge/      # Knowledge base module
│   ├── app-platform-memory/         # Memory module
│   ├── app-platform-model-proxy/    # Model proxy module
│   ├── app-platform-mcp/            # MCP protocol module
│   ├── app-platform-compose/        # Custom table module
│   ├── app-platform-custom-page/    # Custom page module
│   ├── app-platform-eco-market/     # Ecosystem market module
│   ├── app-platform-sandbox/        # Sandbox module
│   ├── app-platform-log/            # Logging module
│   └── platform-system/             # Platform system module
├── fast-boot-dependencies/          # Dependency management
└── specs/                           # Specification documents
```

### Core Modules

| Module | Description |
|--------|-------------|
| **Agent** | Agent creation, session management, message processing |
| **Knowledge** | Knowledge base management, document upload, vector search |
| **Memory** | Long-term memory storage and retrieval |
| **Model Proxy** | Unified model proxy interface |
| **MCP** | Model Context Protocol implementation |
| **Eco Market** | Plugin market and template management |
| **Compose** | Custom table functionality |
| **Custom Page** | No-code page builder |

---

## Configuration

### Configuration File Structure

```
app-platform-web-bootstrap/src/main/resources/
├── application.yml                 # Main configuration file (common settings)
├── application-dev.yml            # Development environment configuration (local use, not committed)
├── application-test.yml           # Test environment configuration (optional)
└── application-prod.yml           # Production environment configuration (optional)
```

### Complete Environment Variable List

#### Database Configuration

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `DB_HOST` | MySQL database host | ✅ | localhost |
| `DB_PORT` | MySQL port | ✅ | 3306 |
| `DB_NAME` | Database name | ✅ | agent_platform |
| `DB_USERNAME` | Database username | ✅ | root |
| `DB_PASSWORD` | Database password | ✅ | - |

#### Custom Table Component Database (Doris not tested, supports MySQL)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `DORIS_HOST` | Doris host | ✅ | localhost |
| `DORIS_DB_NAME` | Doris database name | ✅ | agent_custom_table |
| `DORIS_USERNAME` | Doris username | ✅ | root |
| `DORIS_PASSWORD` | Doris password | ✅ | - |

#### Redis Configuration

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `REDIS_HOST` | Redis host | ✅ | localhost |
| `REDIS_PORT` | Redis port | ✅ | 6379 |
| `REDIS_PASSWORD` | Redis password | ✅ | - |
| `REDIS_DB` | Redis database number | ✅ | 1 |

#### Milvus Vector Database

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `MILVUS_URI` | Milvus connection URI | ✅ | http://localhost:19530 |
| `MILVUS_USER` | Milvus username | ✅ | root |
| `MILVUS_PASSWORD` | Milvus password | ✅ | - |

#### Elasticsearch (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `ES_URL` | Elasticsearch URL | ✅ | http://localhost:9200 |
| `ES_USERNAME` | Elasticsearch username | ✅ | elastic |
| `ES_PASSWORD` | Elasticsearch password | ✅ | - |
| `ES_API_KEY` | Elasticsearch API Key | ✅ | - |

#### Security Configuration

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `JWT_SECRET_KEY` | JWT signing key | ✅ | - (at least 32 characters) |

#### Tencent Cloud COS (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `COS_SECRET_ID` | Tencent Cloud COS secret ID | ✅ | - |
| `COS_SECRET_KEY` | Tencent Cloud COS secret key | ✅ | - |
| `COS_BASE_URL` | COS access domain | ✅ | https://... |

#### File Storage

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `FILE_UPLOAD_FOLDER` | File upload directory | ✅ | /tmp/uploads |
| `FILE_BASE_URL` | File access URL | ✅ | https://yourdomain/api/file |
| `STORAGE_TYPE` | Storage type, currently supports Tencent Cloud COS and local file storage | ✅ | file |

#### External Services (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `CODE_EXECUTE_URL` | Code execution service | ✅ | http://localhost:8020/... |
| `LOG_SERVICE_URL` | Log service URL, can be omitted, deprecated | ✅ | http://localhost:8097 |
| `MCP_PROXY_URL` | MCP proxy URL | ✅ | http://localhost:8020 |

#### Page Application Development Services (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `DEV_SERVER_HOST` | Development server host | ✅ | http://localhost |
| `PROD_SERVER_HOST` | Production server host | ✅ | http://localhost:8099 |
| `BUILD_SERVER_URL` | Build server URL | ✅ | http://localhost:60000/api |
| `AI_AGENT_URL` | AI agent URL | ✅ | http://localhost:8086 |
| `DOCKER_PROXY_URL` | Docker proxy URL | ✅ | http://localhost:8088 |

#### Internal Network Tunnel Proxy Configuration (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `SERVICE_HOST` | Internal service address | ❌ | 127.0.0.1 |
| `BIND_HOST` | Bind address | ❌ | 0.0.0.0 |
| `REVERSE_PORTS` | Port range | ❌ | 30000-40000 |
| `OUTER_HOST` | Client connection IP address | ❌ | - |
| `OUTER_PORT` | Client connection port | ❌ | 6443 |

#### Model Proxy (Optional)

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `MODEL_API_BASE_URL` | Model API URL, corresponding to accessible MODEL_PROXY_PORT | ❌ | - |
| `MODEL_PROXY_ENABLE` | Whether to enable proxy | ❌ | true |
| `MODEL_PROXY_PORT` | Proxy port | ❌ | 18086 |
| `MODEL_PROXY_SAVE_LOG` | Whether to save logs, depends on Elasticsearch | ❌ | true |

---

## Development Guide

### Code Standards

The project follows DDD layered architecture:

```
UI Layer → Adapter Layer → Application Layer → Domain Layer → Infrastructure Layer
```

---

## Contributing

Contributions of code, documentation, and bug reports are welcome!

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

### Contributors

Thanks to all developers who have contributed to this project!

---

## License

This project is licensed under the **Apache License 2.0**.

See [LICENSE](LICENSE) file for details.

---

## Commercial License

### Open Source Use (Free)

This software can be used, modified, and distributed for free, but you must:

- ✅ Retain original copyright notices
- ✅ Include the Apache 2.0 license text
- ✅ State that your product is based on this software

### Commercial License (Paid)

If you need:

- 🏢 **Remove copyright notices** (white-label products)
- 🔄 **OEM partnership** (integrate into your products for resale)
- 🎨 **Custom branding** (use your own brand)
- 🚀 **Priority support** (enterprise-level technical support)

You can apply for a commercial license to obtain additional rights.

For details, see: [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md)