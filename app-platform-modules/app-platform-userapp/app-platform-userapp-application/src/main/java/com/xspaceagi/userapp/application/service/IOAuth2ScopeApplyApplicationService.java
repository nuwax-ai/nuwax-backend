package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;

/**
 * OAuth2 scope 范围变更流程。
 */
public interface IOAuth2ScopeApplyApplicationService {

    /**
     * 提交 scope 变更申请
     */
    OAuth2ScopeApplyModel submit(UserProjectModel project, Long applyUserId, List<String> scopes);

    /** 审核通过 */
    OAuth2ScopeApplyModel approve(Long applyId, Long reviewerId);

    /** 审核拒绝 */
    OAuth2ScopeApplyModel reject(Long applyId, Long reviewerId, String reason);

    /** 条件分页 */
    SuperPage<OAuth2ScopeApplyModel> pageQuery(OAuth2ScopeApplyStatus status, Long projectId, Long current,
            Long pageSize);

    /** 项目最近一条申请（不论状态，设置页展示审核进度 / 拒绝原因用） */
    OAuth2ScopeApplyModel getLatestByProjectId(Long projectId);
}
