package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询页面详情请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageGetRequest extends RepoPageRpcRequest {

    /**
     * 页面ID
     */
    private Long pageId;
}
