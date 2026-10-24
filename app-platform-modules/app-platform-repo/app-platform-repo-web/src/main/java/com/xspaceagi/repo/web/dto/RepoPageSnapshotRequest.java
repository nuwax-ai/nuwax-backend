package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 快照回写请求（前端编辑器防抖触发）
 */
@Data
public class RepoPageSnapshotRequest {

    /**
     * HTML快照（用于搜索/导出/预览）
     */
    private String html;

    /**
     * 编辑器内容JSON快照（TipTap JSON）
     */
    private String contentJson;
}
