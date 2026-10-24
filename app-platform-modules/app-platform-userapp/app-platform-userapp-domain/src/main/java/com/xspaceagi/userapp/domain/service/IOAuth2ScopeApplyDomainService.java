package com.xspaceagi.userapp.domain.service;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

import java.util.Date;

public interface IOAuth2ScopeApplyDomainService {

    OAuth2ScopeApplyModel getById(Long id);

    /** 项目当前的待审核申请（同一项目同一时刻至多一条） */
    OAuth2ScopeApplyModel getPendingByProjectId(Long projectId);

    /** 项目最近一条申请（不论状态，设置页展示用） */
    OAuth2ScopeApplyModel getLatestByProjectId(Long projectId);

    /** 条件分页 */
    SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current, Long pageSize);

    OAuth2ScopeApplyModel create(OAuth2ScopeApplyModel model);

    OAuth2ScopeApplyModel update(OAuth2ScopeApplyModel model);

    /** 条件覆盖待审核申请内容；false = 当前无待审核申请（转为新建） */
    boolean updatePendingContent(Long projectId, Long applyUserId, String oldScopes, String scopes);

    /** 原子状态流转（status='Pending' 守卫）；false = 不存在或已被审核 */
    boolean reviewIfPending(Long id, OAuth2ScopeApplyStatus newStatus, Long reviewerId, String rejectReason,
            Date reviewedAt);

    /** 删除项目全部申请 */
    void deleteByProjectId(Long projectId);
}
