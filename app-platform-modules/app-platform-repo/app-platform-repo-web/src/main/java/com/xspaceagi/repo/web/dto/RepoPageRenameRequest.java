package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 重命名文档页面请求
 */
@Data
public class RepoPageRenameRequest {

    /**
     * 新标题（最长500）
     */
    private String title;
}
