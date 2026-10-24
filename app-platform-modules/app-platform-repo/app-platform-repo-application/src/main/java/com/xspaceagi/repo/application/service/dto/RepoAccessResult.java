package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档访问裁决结果（需求4分享/需求7按人权限统一语义）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoAccessResult {

    /**
     * 可查看
     */
    private boolean viewable;

    /**
     * 可编辑
     */
    private boolean editable;

    /**
     * 可导出
     */
    private boolean exportable;

    /**
     * 可复制（创建副本；链接/匿名不可复制）
     */
    private boolean copyable;

    /**
     * 可分享（分享到空间/创建链接）
     */
    private boolean shareable;

    /**
     * 可删除（仅源空间管理员/属主或页面创建者）
     */
    private boolean deletable;

    /**
     * 可管理（设置文档级按人权限、管理分享记录）
     */
    private boolean manageable;

    /**
     * 可评论（需求5）：严格由评论权限位（PERM_COMMENT）或分享链接 allow_comment 决定，
     * 与可编辑解耦——仅授予编辑位、未勾选「可评论」的分享/按人权限用户不可评论。
     * 创建者/源空间管理员/无按人权限的空间成员因持有 FULL 位而默认可评论；匿名不可评论。
     */
    private boolean commentable;

    /**
     * 权限来源：creator/space-admin/space-member/acl/shared/link/none
     */
    private String via;

    /**
     * 当前用户持有的权限位掩码（组合 {@code RepoConstants.PERM_*}；无查看权限时为 0）。
     * 分享发起时据此裁剪授予他人的权限位，保证「分享的权限不超过自己已有权限」。
     */
    private int permBits;
}
