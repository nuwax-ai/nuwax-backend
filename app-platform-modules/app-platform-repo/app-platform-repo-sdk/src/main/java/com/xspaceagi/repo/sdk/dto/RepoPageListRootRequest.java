package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询空间根节点页面列表请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageListRootRequest extends RepoPageRpcRequest {

    /**
     * 空间ID
     */
    private Long spaceId;
}
