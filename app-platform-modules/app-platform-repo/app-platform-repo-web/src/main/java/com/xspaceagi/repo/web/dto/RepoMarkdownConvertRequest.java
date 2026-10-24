package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * Markdown 导入转换请求（md→html，前端 setContent 进编辑器）
 */
@Data
public class RepoMarkdownConvertRequest {

    /**
     * Markdown 文本
     */
    private String markdown;
}
