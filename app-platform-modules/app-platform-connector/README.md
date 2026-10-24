# app-platform-connector 连接器模块

女娲智能体 OS 的第三方服务连接器：统一目录（provider/action）、凭证保险库、
OAuth/API Key 建连、声明式执行、通用代理与发现型元工具。
设计文档见工作区 `nuwax-connector-design.md`，开发计划见 `nuwax-connector-dev-plan.md`。

## 模块结构

| 子模块 | 职责 |
|---|---|
| connector-spec | 常量、枚举（auth_type/状态/错误码）、ConnectorException |
| connector-sdk | 对外门面 `IConnectorApiService` 与 SDK DTO（供 Agent 运行时依赖） |
| connector-domain | 实体（7 表）+ 服务接口 + 命令/DTO（凭证边界在这一层收敛） |
| connector-infra | AES-256-GCM 加密、Vault 实现、GuardedHttpClient、7 个 Repository、租户旁路 |
| connector-application | Catalog/Connection/执行编排/OAuth/导入导出 实现 |
| connector-ui | REST 控制器、异常 Advice、SDK 实现、DTO 转换 |

依赖方向：ui → application → domain/infra → spec；sdk → spec。

## 数据表（sql/update-20260805.sql）

`connector_provider` / `connector_action` / `connector_connection` /
`connector_credential`（无租户列，仅经 connection 间接访问）/
`connector_oauth_config`（platform+byo 双轨）/ `connector_oauth_state`（主键为 state SHA-256）/
`connector_run_log`（每连接保留 200 条，写入时滚动修剪）。

## 租户策略

7 张表全部经 `ConnectorTenantSupport.withoutTenantFilter()` 旁路租户拦截器，
在每个 Repository 方法内显式携带 tenant/user/space 谓词。
原因：provider 可见性是 `(space_id=0) OR (_tenant_id=t AND space_id=s)`，
credential/oauth_config/oauth_state/action 无租户列，单条租户等值条件无法表达。

## 安全模型

- **凭证边界铁律**：明文只在 connector 模块内流动（Vault reveal → 执行器注入请求头）；
  一切对外返回（REST/SDK/日志/审计/市场快照）只有 profile（accountId/displayName/avatar）。
- **加密**：AES-256-GCM，格式 `enc:v1:{salt}:{iv}:{tag}:{data}`，记录密钥 = SHA-256(主密钥||salt)；
  client_secret 与 PKCE verifier 同格式加密。
- **SSRF（GuardedHttpClient，永远生效）**：仅 http/https；URL 字面量 + DNS 预解析双校验；
  loopback/link-local/云元数据（含 169.254.169.254 与 100.100.100.200）永不放行；
  私网段默认拒绝（`allow-private` opt-in）；重定向逐跳重校验、跨源剥离凭证头；
  超时与响应体上限强制。
- **OAuth**：仅用于建立连接，不颁发平台会话；state 一次性原子消费防重放（15 分钟 TTL）；
  PKCE S256；刷新轮换走 credential.revision 乐观锁写回，冲突重读。
- **审计**：run_log 只存输入字段名 + 输出预览（500 字符截断）+ HTTP 状态；SSRF 拦截记 policy_denied。
- **权限**：用户侧查空间权限（SpacePermissionService）；系统侧治理接口沿用 `@RequireResource(MCP_DEV)`。

## 执行路径

- **A 声明式**：action.http_spec 描述 method/path/query/headers/body/响应抽取，
  映射值为输入字段名或 `literal:` 字面量，path 支持 `{var}` 占位。
- **B CUSTOM**：仅官方层可定义；运行时实现 `ConnectorRuntime` Bean 注册进
  `ConnectorRuntimeRegistry`，未注册返回 `exec_custom_not_registered`（本期无内置实现）。
- **C 通用代理**：provider.proxy_enabled=1 时兜底；相对路径白名单校验，禁 `..`；
  客户端自带鉴权头一律不转发。

统一结果信封：`{success, message, data, errorCode, meta{executionId, connectionProfile, ...}}`。
未建连返回 `connection_required` + `meta.authorizeHint`（OAuth 授权或直填入口）。

## API 一览

| 方法 路径 | 说明 |
|---|---|
| GET `/api/connector/providers` | 可见 provider 分页（含 connected 标记） |
| GET `/api/connector/providers/{service}` | provider 详情 + actions |
| POST/PUT/DELETE `/api/connector/providers[...]` | 空间侧声明式 CRUD（禁官方层） |
| POST/PUT/DELETE `/api/connector/.../actions[...]` | 空间侧 action CRUD（禁 CUSTOM） |
| POST `/api/connector/import` `/import/apply` GET `/export` | 目录导入导出（MCP_DEV，写官方目录） |
| POST `/api/connector/connections/api-key` | 直填凭证建连（api_key/bearer/custom） |
| GET/DELETE `/api/connector/connections[...]` | 我的连接列表/详情（仅 profile）/断开 |
| POST `/api/connector/connections/{id}/verify` | whoami 验证并回填 profile |
| GET `/api/connector/oauth/authorize` | 发起授权（state+PKCE） |
| GET `/api/connector/oauth/callback` | 回调：换 token、建连、HTML 完成页（**免登录**） |
| POST `/api/connector/oauth/exchange` | 同上处理逻辑的 JSON 版：提交 code+state 回结构化结果（**免登录**，供 Agent/程序判定成败） |
| POST/GET `/api/connector/oauth/byo-config` | 用户自带 OAuth App 配置 |
| POST `/api/connector/actions/{id}/test` | 调试执行（必须显式 connectionId） |
| POST `/api/connector/runtime/execute` | 执行入口（Agent/运行时） |
| POST `/api/connector/proxy/{service}` | 通用代理 |
| GET `/api/connector/discover/providers|search|guide` | 发现型元工具 REST 镜像 |
| PUT `/api/system/connector/providers/{service}` | 启停 provider（MCP_DEV） |
| PUT `/api/system/connector/actions/{id}` | deprecated 标记（MCP_DEV） |
| POST/GET `/api/system/connector/oauth-config` | 平台公共 OAuth App 配置（MCP_DEV） |
| GET `/api/system/connector/runs` | 执行审计查询（MCP_DEV） |

Agent 侧不经 REST：注入 `IConnectorApiService`（search/guide/execute/listProviders/hasActiveConnection）。

## 配置项

```yaml
nuwax:
  connector:
    guard:
      enforce: true            # false=观察模式（只告警不拦截）
      allow-private: false     # 私网段 opt-in（自托管场景）
      timeout-ms: 60000        # 出站全局超时上限
      max-response-bytes: 1048576
    oauth:
      redirect-base-url: ""    # 平台对外基地址，OAuth 必填
```

主密钥：环境变量 `NUWAX_CONNECTOR_ENCRYPTION_KEY`（base64 32 字节或 64 位 hex）。
**惰性校验**：未配置仅启动告警，首次使用 Vault 时才抛 `key_not_configured`
（避免未启用连接器的存量部署因缺密钥启动失败）。

## 已知偏差（与设计文档的差异，均需产品确认）

1. **密钥惰性校验**：设计原为"启动即校验"，实现改为启动 WARN + 首次使用报错（理由见上）。
2. **管理侧权限码**：沿用已播种的 `mcp_dev` 资源码作为系统接口门禁；未新增
   ResourceEnum/菜单资源（那需要动存量代码与 update-SQL 播种，后置审批）。
3. **导入导出定位**：实现为 MCP_DEV 门禁的管理侧能力（写官方目录 space_id=0）；
   空间级导入（写空间目录）未在本期实现。
4. **多副本 dry-run**：import 会话存内存（30 分钟 TTL），apply 按存储 bundle 重算 diff；
   跨节点命中失败返回 `import_diff_mismatch`，客户端重跑 dry-run 即可。
5. **CUSTOM 运行时**：接口与注册表已就位，本期无内置实现（P5 规划）。
