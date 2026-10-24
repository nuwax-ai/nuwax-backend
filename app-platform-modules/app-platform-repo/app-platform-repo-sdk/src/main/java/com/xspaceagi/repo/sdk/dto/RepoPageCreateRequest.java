package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 新建页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageCreateRequest extends RepoPageRpcRequest {

    /**
     * 空间ID
     */
    private Long spaceId;

    /**
     * 父页面ID，null=根节点
     */
    private Long parentId;

    /**
     * 标题
     */
    private String title;

    /**
     * 页面类型：doc富文本 / sheet表格 / datatable多维表格 / folder目录（纯容器节点，无正文快照、不进搜索索引）。
     * null或空=doc，非法值抛参数异常。
     */
    private String pageType;

    /**
     * 导入源文件扩展名，原生创建传 null
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key，原生创建传 null
     */
    private String originalFileKey;
}
