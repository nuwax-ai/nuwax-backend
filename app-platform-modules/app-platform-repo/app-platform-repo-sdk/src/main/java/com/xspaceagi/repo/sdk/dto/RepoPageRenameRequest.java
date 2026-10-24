package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 重命名页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageRenameRequest extends RepoPageRpcRequest {

    /**
     * 页面ID
     */
    private Long pageId;

    /**
     * 新标题（最长500）
     */
    private String title;
}
