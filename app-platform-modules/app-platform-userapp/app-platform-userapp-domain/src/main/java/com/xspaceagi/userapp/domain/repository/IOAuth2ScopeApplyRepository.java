package com.xspaceagi.userapp.domain.repository;

import java.util.Date;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

public interface IOAuth2ScopeApplyRepository {

    OAuth2ScopeApplyModel getById(Long id);

    /** 项目当前的待审核申请（同一项目同一时刻至多一条） */
    OAuth2ScopeApplyModel getPendingByProjectId(Long projectId);

    /** 项目最近一条申请（不论状态，设置页展示用） */
    OAuth2ScopeApplyModel getLatestByProjectId(Long projectId);

    /** 条件分页 */
    SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current, Long pageSize);

    Long add(OAuth2ScopeApplyModel model);

    void updateById(OAuth2ScopeApplyModel model);

    /**
     * 条件覆盖项目的待审核申请内容。
     * 返回 false 表示当前没有待审核申请（调用方转为新建）。
     */
    boolean updatePendingContent(Long projectId, Long applyUserId, String oldScopes, String scopes);

    /**
     * 原子状态流转。返回 false 表示该申请不存在或已被审核（并发审核/已被重新提交时后到者失败）。
     */
    boolean reviewIfPending(Long id, OAuth2ScopeApplyStatus newStatus, Long reviewerId, String rejectReason,
            Date reviewedAt);

    /** 删除项目全部申请 */
    void deleteByProjectId(Long projectId);
}
