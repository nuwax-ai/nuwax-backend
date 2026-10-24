package com.xspaceagi.repo.sdk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 文档页面投影（SDK 对外，脱离 domain model）。
 * <p>
 * 字段与 {@code RepoPageDomain} 一一对应；不承载 ydoc_state 二进制（CRDT 权威源由 Node Sidecar 直读）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageRpcDto {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 所属空间（平台 space 表）
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
     * 短链ID（对外路由使用，不暴露自增主键）
     */
    private String slugId;

    /**
     * 同级排序（fractional index）
     */
    private String position;

    /**
     * 页面类型：doc富文本 / sheet表格 / datatable多维表格 / folder目录
     */
    private String pageType;

    /**
     * ProseMirror JSON 快照
     */
    private String contentSnapshot;

    /**
     * 渲染HTML快照：只读/搜索/导出用
     */
    private String htmlSnapshot;

    /**
     * 正文 HTML 长度（字节）
     */
    private Long contentSize;

    /**
     * 正文媒体大小合计（字节）
     */
    private Long mediaSize;

    /**
     * 创建人ID
     */
    private Long creatorId;

    /**
     * 最后编辑人ID
     */
    private Long lastEditorId;

    /**
     * 最后编辑时间
     */
    private Date editedAt;

    /**
     * 导入源文件扩展名（原生创建为 null）
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key（原生创建为 null）
     */
    private String originalFileKey;

    /**
     * 历史编辑者ID列表
     */
    private List<Long> editorIds;

    /**
     * 文档级按人权限开关：0=关闭（成员默认可读写），1=开启（未配置成员降为仅阅读）
     */
    private Integer aclEnabled;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
