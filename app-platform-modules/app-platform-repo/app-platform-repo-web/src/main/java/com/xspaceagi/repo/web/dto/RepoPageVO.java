package com.xspaceagi.repo.web.dto;

import com.xspaceagi.repo.application.service.dto.RepoAccessResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 文档页面视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVO {

    private Long id;

    private Long spaceId;

    private Long parentId;

    private String title;

    private String slugId;

    private String position;

    /**
     * 页面类型：doc富文本 sheet表格
     */
    private String pageType;

    /**
     * 导入源文件扩展名（需求2），原生创建为 null
     */
    private String sourceExt;

    /**
     * 导入时原件的文件 key（需求3：有值才显示「下载原始文件」），原生创建为 null
     */
    private String originalFileKey;

    /**
     * 历史编辑者ID列表（需求5）
     */
    private List<Long> editorIds;

    /**
     * 历史编辑者展示名（editorId → 昵称/用户名）。后端按 userId 补齐，前端免查空间成员；
     * 用户已注销/查不到时该 id 不在此映射（前端回落「用户 N」）
     */
    private Map<Long, String> editorNames;

    /**
     * 历史编辑者头像原值（editorId → avatar），前端 normalize；查不到不放入
     */
    private Map<Long, String> editorAvatars;

    /**
     * 文档级按人权限开关（需求7）：0=关闭 1=开启
     */
    private Integer aclEnabled;

    /**
     * 正文大小（html_snapshot 字符数，空间列表展示用），树接口可空
     */
    private Long contentSize;

    /**
     * 正文媒体字节数（content_snapshot 内 image/attachment/video/audio 节点 attrs.size 字节数之和）。
     * 由快照回写时预计算落库 media_size 列，列表/树轻量查询直接取列。
     * 列表「大小」列 = contentSize + attachmentSize（正文快照字符数 + 正文媒体字节数）。
     * 字段名沿用 attachmentSize 以保持前端兼容。
     */
    private Long attachmentSize;

    private Long creatorId;

    /**
     * 创建者展示名（昵称优先）。后端按 userId 补齐；查不到为 null（前端回落空间成员映射/「用户 N」）
     */
    private String creatorName;

    /**
     * 创建者头像原值，前端 normalize；查不到为 null
     */
    private String creatorAvatar;

    /**
     * 分享者（发起分享链接的用户）ID：仅通过有效分享链接访问时填充，其余为 null
     */
    private Long ownerId;

    /**
     * 分享者展示名（昵称优先）：分享链接进入且当前用户非源空间成员（拿不到空间名）时，
     * 前端面包屑首段回落到此展示分享者。仅 getPage/getPageBySlug 带 shareToken 时填充
     */
    private String ownerName;

    private Long lastEditorId;

    private Date editedAt;

    private Date created;

    private Date modified;

    /**
     * 当前用户对该页面的访问权限（仅详情接口填充）
     */
    private RepoAccessResult myAccess;
}
