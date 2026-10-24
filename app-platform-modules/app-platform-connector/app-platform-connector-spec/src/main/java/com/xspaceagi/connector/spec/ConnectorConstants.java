package com.xspaceagi.connector.spec;

/**
 * 连接器模块常量。
 */
public final class ConnectorConstants {

    private ConnectorConstants() {
    }

    /** 凭证密文前缀（AES-256-GCM，per-record 随机 salt） */
    public static final String CIPHER_PREFIX = "enc:v1:";

    /** 加密主密钥环境变量名 */
    public static final String ENCRYPTION_KEY_ENV = "NUWAX_CONNECTOR_ENCRYPTION_KEY";

    /** 执行时未找到当前用户连接时的引导错误码 */
    public static final String ERR_CONNECTION_REQUIRED = "connection_required";

    /** 发现型搜索默认返回条数 */
    public static final int SEARCH_DEFAULT_LIMIT = 20;

    /** 发现型搜索最大返回条数 */
    public static final int SEARCH_MAX_LIMIT = 50;

    /**
     * 执行结果 meta 键：运行时声明「本次 data 就是凭证明文」。
     * <p>
     * 审计侧据此整体省略 outputPreview。之所以需要显式声明而不是靠既有脱敏兜住：
     * {@code preview} 的脱敏方式是「把 credentialJson 的叶子值逐个替换成 ***」，
     * 它只能盖住凭证 JSON 里有的字段；像 client_secret 这种来自 oauth-config 的值
     * 不在该集合内，会被原样写进 connector_run_log。
     */
    public static final String META_SENSITIVE_PAYLOAD = "sensitivePayload";
}
