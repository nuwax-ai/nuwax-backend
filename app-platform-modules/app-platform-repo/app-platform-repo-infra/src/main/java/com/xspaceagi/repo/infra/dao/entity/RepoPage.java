package com.xspaceagi.repo.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 文档页面实体（核心表）
 * @TableName repo_page
 */
@TableName(value = "repo_page")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPage {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
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
     * 短链ID（对外路由使用）
     */
    private String slugId;

    /**
     * 同级排序（fractional index）
     */
    private String position;

    /**
     * 页面类型：doc富文本 sheet表格（RepoPageTypeEnum）
     */
    private String pageType;

    /**
     * ProseMirror JSON 快照
     */
    private String contentSnapshot;

    /**
     * 正文媒体字节数（图片/附件/视频/音频 size 合计）。
     * 由 Java 快照回写时解析 content_snapshot 计算落库，列表/树轻量查询直接取列（真实表列）。
     */
    private Long mediaSize;

    /**
     * 渲染HTML快照：只读/搜索/导出用
     */
    private String htmlSnapshot;

    /**
     * Yjs 二进制（CRDT 唯一权威源）。
     * 由 Node Sidecar 经受限账号列级授权直读直写，Java 侧一般不触碰。
     */
    private byte[] ydocState;

    /**
     * 正文 HTML 长度（字节），树/列表轻量查询用 SQL 侧 CHAR_LENGTH 计算，非真实表列。
     * 仅用于列表「大小」列展示，避免列表查询把 html_snapshot 大字段整体拉出。
     */
    @TableField(exist = false)
    private Long contentSize;

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
     * 导入源文件扩展名（需求2）
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key（需求3：原始数据导出），原生创建为 null
     */
    private String originalFileKey;

    /**
     * 历史编辑者ID JSON数组（需求5）
     */
    private String editorIds;

    /**
     * 文档级按人权限开关（需求7）：0关闭 1开启
     */
    private Integer aclEnabled;

    /**
     * 软删除标记：1有效 0删除
     */
    private Integer yn;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
