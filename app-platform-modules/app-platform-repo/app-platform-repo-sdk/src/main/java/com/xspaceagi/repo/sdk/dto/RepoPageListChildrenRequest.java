package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询页面直接子页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageListChildrenRequest extends RepoPageRpcRequest {

    /**
     * 页面ID
     */
    private Long pageId;
}
