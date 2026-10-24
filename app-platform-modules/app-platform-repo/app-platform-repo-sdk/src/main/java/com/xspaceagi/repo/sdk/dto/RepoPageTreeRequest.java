package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询空间完整页面树请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageTreeRequest extends RepoPageRpcRequest {

    /**
     * 空间ID
     */
    private Long spaceId;
}
