package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 新建文档页面请求
 */
@Data
public class RepoPageCreateRequest {

    /**
     * 父页面ID，null=根节点
     */
    private Long parentId;

    /**
     * 页面标题
     */
    private String title;

    /**
     * 页面类型：doc富文本 sheet表格，空=doc（RepoPageTypeEnum）
     */
    private String pageType;

    /**
     * 导入源文件扩展名（需求2：标题不含后缀，扩展名独立保存），原生创建留空
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key（需求3：原始数据导出；前端先上传原件再创建页面），原生创建留空
     */
    private String originalFileKey;
}
