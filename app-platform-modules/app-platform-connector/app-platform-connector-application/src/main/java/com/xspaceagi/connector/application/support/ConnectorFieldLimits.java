package com.xspaceagi.connector.application.support;

import org.apache.commons.lang3.StringUtils;

/**
 * 落库字段的长度边界工具。
 * <p>
 * 入口 DTO 用 {@code @Size} 拦用户输入，但「缺省值」这条路径绕过了校验：
 * 连接别名缺省取 provider 展示名（connector_provider.display_name 列宽 128），
 * 而 connector_connection.name 列宽只有 64，直接落库会撞 data too long。
 * 这类缺省值不该让用户吃报错，按列宽截断即可——别名只是展示用。
 */
public final class ConnectorFieldLimits {

    /** connector_connection.name 列宽 */
    public static final int CONNECTION_NAME_MAX = 64;

    private ConnectorFieldLimits() {
    }

    /**
     * 连接别名：优先用户输入，为空则回退 provider 展示名，最后按列宽截断。
     * 截断只影响展示别名，不影响凭证与绑定关系。
     */
    public static String connectionAlias(String preferred, String fallback) {
        String alias = StringUtils.defaultIfBlank(preferred, fallback);
        if (alias != null && alias.length() > CONNECTION_NAME_MAX) {
            return alias.substring(0, CONNECTION_NAME_MAX);
        }
        return alias;
    }
}
