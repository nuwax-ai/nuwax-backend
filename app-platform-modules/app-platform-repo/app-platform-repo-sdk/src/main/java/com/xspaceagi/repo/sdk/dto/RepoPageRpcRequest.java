package com.xspaceagi.repo.sdk.dto;

import lombok.Data;

/**
 * 资料库 RPC 请求基类：携带调用方显式传入的操作者上下文。
 * <p>
 * userId + tenantId 由调用方显式传入（支持后台任务 / 以指定用户身份调用），
 * 实现层据此切换 {@link com.xspaceagi.system.spec.common.RequestContext} 后再执行权限裁决与租户隔离。
 */
@Data
public class RepoPageRpcRequest {

    /**
     * 操作者用户ID
     */
    private Long userId;

    /**
     * 操作者租户ID
     */
    private Long tenantId;
}
