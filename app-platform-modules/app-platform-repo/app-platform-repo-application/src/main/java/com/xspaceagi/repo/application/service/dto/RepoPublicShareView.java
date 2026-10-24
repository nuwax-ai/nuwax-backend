package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 匿名分享链接内容视图（需求4：只读分享页数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPublicShareView {

    private Long pageId;

    private String slugId;

    private String title;

    /**
     * 页面所属空间（需求4：登录态分享页渲染左侧目录用）
     */
    private Long spaceId;

    /**
     * ProseMirror JSON 快照（前端只读编辑器渲染用）
     */
    private String contentJson;

    /**
     * HTML快照（图片 src 改写/降级渲染用）
     */
    private String html;

    /**
     * 导入源文件扩展名，可空
     */
    private String sourceExt;

    /**
     * 页面类型：doc富文本 sheet表格
     */
    private String pageType;

    private Long creatorId;

    /**
     * 创建者展示名（昵称优先）。后端按 userId 补齐；查不到为 null（前端回落「用户 N」）
     */
    private String creatorName;

    /**
     * 创建者头像原值，前端 normalize；查不到为 null
     */
    private String creatorAvatar;

    /**
     * 分享者（发起分享链接的用户）ID：分享页面包屑在拿不到空间名称时展示分享者
     */
    private Long ownerId;

    /**
     * 分享者展示名（昵称优先）。后端按 link.createdBy 补齐；查不到为 null
     */
    private String ownerName;

    /**
     * 分享者头像原值，前端 normalize；查不到为 null
     */
    private String ownerAvatar;

    private Date editedAt;

    /**
     * 是否允许评论（需求5）：来自链接 allow_comment；匿名/不允许时为 false
     */
    private Boolean commentable;

    /**
     * 链接的评论开关原值（前端展示/管理用），1=允许 0=不允许
     */
    private Integer allowComment;

    /**
     * 是否允许导出（本轮：链接权限位 PERM_EXPORT；供分享页导出按钮门控）
     */
    private Boolean exportable;

    /**
     * 是否允许复制（本轮：链接权限位 PERM_COPY；未勾选时分享页禁止取词/隐藏创建副本）
     */
    private Boolean copyable;
}
