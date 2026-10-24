package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 页面搜索请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageSearchRequest extends RepoPageRpcRequest {

    /**
     * 空间ID，可空=全租户
     */
    private Long spaceId;

    /**
     * 关键字，可空=仅列出
     */
    private String keyword;

    /**
     * 分页起始，可空默认0
     */
    private Integer from;

    /**
     * 分页大小，可空默认20
     */
    private Integer size;
}
