package com.xspaceagi.connector.domain.exec;

import com.xspaceagi.connector.domain.entity.ConnectorConnection;

/**
 * OAuth 访问令牌提供者（执行引擎与 OAuth 服务的解耦点）。
 * <p>
 * S6 执行引擎通过本接口取"当前有效的 access_token"；S7 OAuth 服务实现本接口，
 * 负责到期判断、refresh 轮换与乐观锁写回。未实现前 oauth2 连接执行将得到
 * OAUTH_CONFIG_MISSING 类错误。
 */
public interface OAuthTokenProvider {

    /**
     * 返回该连接当前可用的 access_token（必要时先刷新）。
     *
     * @throws com.xspaceagi.connector.spec.exception.ConnectorException 刷新失败/凭证缺失时
     */
    String validAccessToken(ConnectorConnection connection);
}
