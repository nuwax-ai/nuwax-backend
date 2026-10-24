package com.xspaceagi.connector.domain.exec;

import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * CUSTOM 运行时执行上下文（路径 B）。
 * <p>
 * credentialJson 只在 connector 模块内部流动；运行时实现必须是模块内注册的 Bean，
 * 平台其它模块拿不到该上下文。
 */
@Getter
@Builder
public class RuntimeContext {

    private final ConnectorProvider provider;

    private final ConnectorAction action;

    private final ConnectorConnection connection;

    /** 解密后的凭证 JSON（仅模块内） */
    private final String credentialJson;

    /** 用户输入参数 */
    private final Map<String, Object> args;

    private final Long tenantId;

    private final Long userId;
}
