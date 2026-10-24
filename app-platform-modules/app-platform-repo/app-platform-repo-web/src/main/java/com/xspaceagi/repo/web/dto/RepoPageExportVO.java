package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 页面导出视图对象（Markdown）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageExportVO {

    /**
     * 页面标题
     */
    private String title;

    /**
     * 导出的 Markdown 文本
     */
    private String markdown;
}
