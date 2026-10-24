package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 最近编辑页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageRecentRequest extends RepoPageRpcRequest {

    /**
     * 条数上限（&lt;=0 默认12，封顶50）
     */
    private int size;
}
