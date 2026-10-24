package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 文档页面领域模型（不含 ydoc_state 二进制，CRDT 权威源由 Node Sidecar 经受限账号直读）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageDomain {

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
     * 页面类型：doc富文本 sheet表格（RepoPageTypeEnum.code）
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
     * 正文 HTML 长度（字节），树/列表轻量查询由 SQL 侧 CHAR_LENGTH(html_snapshot) 计算，
     * 避免列表查询整体拉出 html_snapshot 大字段；完整查询（findBySlugId 等）下为 null。
     */
    private Long contentSize;

    /**
     * 正文媒体大小合计（字节）：content_snapshot 内 image/attachment/video/audio 节点
     * attrs.size 之和。由 Java 快照回写时计算落库 media_size 列，列表/树轻量查询直接取列，
     * 避免为算大小而解析 content_snapshot JSON；完整查询（findBySlugId 等）下为 null。
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
     * 导入源文件扩展名（需求2：标题不含后缀，扩展名独立保存；原生创建为 null）
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key（需求3：原始数据导出），原生创建为 null
     */
    private String originalFileKey;

    /**
     * 历史编辑者ID列表（需求5：随快照回写维护，去重、上限 200）
     */
    private List<Long> editorIds;

    /**
     * 文档级按人权限开关（需求7）：0=关闭（成员默认可读写），1=开启（未配置成员降为仅阅读）
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
