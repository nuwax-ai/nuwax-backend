package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回写内容快照请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageSnapshotRequest extends RepoPageRpcRequest {

    /**
     * 页面ID
     */
    private Long pageId;

    /**
     * HTML快照
     */
    private String html;

    /**
     * 编辑器内容JSON快照
     */
    private String contentJson;
}
