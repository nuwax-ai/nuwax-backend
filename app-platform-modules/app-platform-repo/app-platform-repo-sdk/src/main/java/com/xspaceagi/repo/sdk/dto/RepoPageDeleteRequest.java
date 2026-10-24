package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 删除页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageDeleteRequest extends RepoPageRpcRequest {

    /**
     * 页面ID
     */
    private Long pageId;
}
