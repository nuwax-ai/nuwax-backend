package com.xspaceagi.connector.spec.error;

/**
 * 连接器模块错误码表（设计文档 §7 统一信封的 errorCode 字段）。
 * code 为对外稳定契约，禁止随意改动；新增只能追加。
 */
public enum ConnectorErrorCode {

    // ---------- 通用 ----------
    VALIDATION_ERROR("validation_error", "参数校验失败", 400),
    KEY_NOT_CONFIGURED("key_not_configured", "连接器加密主密钥未配置（环境变量 NUWAX_CONNECTOR_ENCRYPTION_KEY）", 500),

    // ---------- Vault ----------
    CIPHERTEXT_INVALID("ciphertext_invalid", "凭证密文格式非法", 500),
    DECRYPT_FAILED("decrypt_failed", "凭证解密失败（密文被篡改或密钥不匹配）", 500),
    CREDENTIAL_NOT_FOUND("credential_not_found", "凭证不存在", 404),
    CREDENTIAL_REVISION_CONFLICT("credential_revision_conflict", "凭证并发写回冲突", 409),

    // ---------- Catalog ----------
    PROVIDER_NOT_FOUND("provider_not_found", "连接器不存在", 404),
    PROVIDER_DISABLED("provider_disabled", "连接器已停用", 403),
    PROVIDER_EXISTS("provider_exists", "连接器 service 已存在", 409),
    PROVIDER_IN_USE("provider_in_use", "连接器仍存在连接或绑定，不能删除", 409),
    ACTION_NOT_FOUND("action_not_found", "动作不存在", 404),
    ACTION_DISABLED("action_disabled", "动作已停用", 403),
    CUSTOM_RUNTIME_FORBIDDEN("custom_runtime_forbidden", "用户只能创建声明式连接器，CUSTOM 执行器仅官方层可建", 403),
    PROTECTED_ZONE("protected_zone", "条目处于手工保护区（managed_by=user），导入不覆盖", 409),

    // ---------- Connection ----------
    CONNECTION_NOT_FOUND("connection_not_found", "连接不存在", 404),
    CONNECTION_REQUIRED("connection_required", "当前用户未建立该连接器连接，请先授权", 401),
    CONNECTION_EXPIRED("connection_expired", "连接凭证已过期，请重新授权", 401),
    // 与 CONNECTION_REQUIRED 分开：连接是建了的，只是被用户自己停用了。
    // 复用 connection_required 会把「去连接」当成解法，把用户引到错误的方向（重连会多出一条连接）。
    CONNECTION_DISABLED("connection_disabled", "连接已停用，请在连接管理中启用后再执行", 401),
    UNSUPPORTED_AUTH_TYPE("unsupported_auth_type", "该认证类型不支持此操作", 400),
    VERIFY_FAILED("verify_failed", "连接验活失败", 502),

    // ---------- 执行 ----------
    SSRF_BLOCKED("ssrf_blocked", "目标地址被出站安全策略拦截", 403),
    UPSTREAM_ERROR("upstream_error", "上游服务返回错误", 502),
    UPSTREAM_TIMEOUT("upstream_timeout", "上游服务调用超时", 504),
    UPSTREAM_RESPONSE_TOO_LARGE("upstream_response_too_large", "上游响应体超过大小上限", 502),
    EXEC_INPUT_INVALID("exec_input_invalid", "执行输入不符合 action 参数定义", 400),
    EXEC_CUSTOM_NOT_REGISTERED("exec_custom_not_registered", "CUSTOM 执行器未注册", 500),

    // ---------- 代理 ----------
    PROXY_NOT_ENABLED("proxy_not_enabled", "该连接器未开启通用代理", 403),
    PROXY_PATH_INVALID("proxy_path_invalid", "代理路径非法（必须为相对路径且无穿越）", 400),
    PROXY_METHOD_NOT_ALLOWED("proxy_method_not_allowed", "代理方法不在白名单", 405),

    // ---------- OAuth ----------
    OAUTH_CONFIG_MISSING("oauth_config_missing", "该连接器缺少 OAuth App 配置（平台公共 App 或 BYO）", 400),
    OAUTH_STATE_INVALID("oauth_state_invalid", "OAuth state 无效或已被消费", 400),
    OAUTH_STATE_EXPIRED("oauth_state_expired", "OAuth 授权已超时，请重新发起", 400),
    OAUTH_EXCHANGE_FAILED("oauth_exchange_failed", "OAuth code 换取 token 失败", 502),
    OAUTH_REFRESH_FAILED("oauth_refresh_failed", "OAuth token 刷新失败", 502),

    // ---------- 导入导出 ----------
    IMPORT_BUNDLE_INVALID("import_bundle_invalid", "导入包格式非法", 400),
    IMPORT_DIFF_MISMATCH("import_diff_mismatch", "apply 与最近一次 dry-run 的 diff 不一致，请重新 dry-run", 409);

    private final String code;
    private final String message;
    private final int httpStatus;

    ConnectorErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
