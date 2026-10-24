package com.xspaceagi.connector.domain.service;

import com.xspaceagi.connector.domain.command.CreateConnectionCommand;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;

import java.util.List;

/**
 * 用户连接服务（本期只做个人连接，设计文档 §9.2）。
 * <p>
 * 凭证边界铁律：本服务对外只暴露 profile，任何返回值不得包含凭证明文/密文。
 */
public interface ConnectorConnectionService {

    /**
     * 直填凭证建连接（api_key/bearer/custom/no_auth）。OAuth 走授权流程，不走此入口。
     */
    ConnectorConnection createDirectConnection(Long tenantId, Long userId, CreateConnectionCommand command);

    /**
     * 我的连接列表。
     */
    List<ConnectorConnection> listMyConnections(Long tenantId, Long userId, String providerService);

    /**
     * 归属校验读取（仅本人）。
     */
    ConnectorConnection requireOwned(Long connectionId, Long userId);

    /**
     * 执行时连接解析：跟随会话用户（§4.8 隔离维度 3）。
     * 未找到返回 null（调用方转 connection_required 引导错误）。
     */
    ConnectorConnection resolveForExecution(Long tenantId, Long userId, String providerService);

    /**
     * 启用/停用连接：仅本人可操作。
     * <p>
     * 停用不走 {@link #disconnect}——凭证保留、连接仍在列表与目录里可见（§「连接器还是看得到」），
     * 只是不再被 {@link #resolveForExecution} 解析到，因此执行侧自然跳过；随时可启用回来，不必重新授权。
     *
     * @return 更新后的连接（含新 status），便于调用方直接回给客户端
     */
    ConnectorConnection setEnabled(Long connectionId, Long userId, boolean enabled);

    /**
     * 断开连接：凭证连同 credential 行删除。
     */
    void disconnect(Long connectionId, Long userId);
}
