package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档分享状况（需求4）：空间分享列表 + 分享链接列表 + 当前用户管理标志。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareStatus {

    /**
     * 空间分享记录（含目标空间名称与类型，授权管理按 Team/Group 分组用）
     */
    private List<RepoShareSpaceVO> spaceShares;

    /**
     * 个人分享记录（需求3）
     */
    private List<RepoShareUserVO> userShares;

    /**
     * 分享链接记录
     */
    private List<RepoShareLinkDomain> links;

    /**
     * 当前用户可管理（取消他人分享/删链接）
     */
    private boolean manageable;

    /**
     * 当前用户可发起分享
     */
    private boolean shareable;

    /**
     * 当前用户对该文档持有的权限位掩码（组合 {@code RepoConstants.PERM_*}）。
     * 前端分享弹层据此禁用越权的权限勾选（如自己无可编辑位则不可授予「可编辑」）。
     */
    private int myPermBits;
}
