package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 沙箱文档 API：创建页面请求（在 RepoPageCreateRequest 基础上多带 spaceId，适配统一前缀 /api/v1/4sandbox/repo）。
 */
@Data
public class SandboxPageCreateRequest {

    /** 目标空间ID（必填） */
    private Long spaceId;

    /** 父页面ID，null=根节点 */
    private Long parentId;

    /** 页面标题 */
    private String title;

    /** 页面类型：doc富文本 sheet表格，空=doc（RepoPageTypeEnum） */
    private String pageType;

    /** 导入源文件扩展名（原生创建留空） */
    private String sourceExt;

    /** 导入时原件的文件 key（原生创建留空） */
    private String originalFileKey;
}
