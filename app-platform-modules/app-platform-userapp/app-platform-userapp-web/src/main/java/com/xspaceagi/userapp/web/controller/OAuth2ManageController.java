package com.xspaceagi.userapp.web.controller;

import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.service.IOAuth2ScopeApplyApplicationService;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.web.dto.OAuth2ScopeApplyQueryReq;
import com.xspaceagi.userapp.web.dto.OAuth2ScopeApplyRejectReq;
import com.xspaceagi.userapp.web.dto.OAuth2ScopeApplyVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

/**
 * OAuth2 scope 范围变更审核（管理端）
 */
@Tag(name = "OAuth2 scope 审核", description = "OAuth2 scope change review (admin only)")
@RestController
@RequestMapping("/api/system/oauth2")
@Slf4j
public class OAuth2ManageController extends BaseController {

    @Resource
    private IOAuth2ScopeApplyApplicationService oauth2ScopeApplyApplicationService;

    @Resource
    private IUserProjectApplicationService userProjectApplicationService;

    @RequireResource(OAUTH2_SCOPE_AUDIT_QUERY)
    @Operation(summary = "分页查询 scope 变更申请")
    @PostMapping("/page-query")
    public ReqResult<SuperPage<OAuth2ScopeApplyVo>> pageQuery(@RequestBody PageQueryVo<OAuth2ScopeApplyQueryReq> req) {
        getUser();
        if (req == null) {
            req = new PageQueryVo<>();
        }
        OAuth2ScopeApplyQueryReq filter = req.getQueryFilter() == null ? new OAuth2ScopeApplyQueryReq() : req.getQueryFilter();
        SuperPage<OAuth2ScopeApplyModel> page = oauth2ScopeApplyApplicationService.pageQuery(filter.getStatus(),
                filter.getProjectId(), req.getPageNo(), req.getPageSize());
        // 批量取所属应用信息，避免逐行查询
        List<Long> projectIds = page.getRecords().stream().map(OAuth2ScopeApplyModel::getProjectId).distinct().toList();
        Map<Long, UserProjectModel> projects = userProjectApplicationService.listByIds(projectIds).stream()
                .collect(Collectors.toMap(UserProjectModel::getId, Function.identity()));
        List<OAuth2ScopeApplyVo> records = page.getRecords().stream()
                .map(apply -> toVo(apply, projects.get(apply.getProjectId()))).toList();
        return ReqResult.success(SuperPage.build(page, records));
    }

    @RequireResource(OAUTH2_SCOPE_AUDIT_PASS)
    @Operation(summary = "审核通过（目标 scope 写回生效）")
    @PostMapping("/approve/{applyId}")
    public ReqResult<Void> approve(@PathVariable("applyId") Long applyId) {
        UserContext userContext = getUser();
        oauth2ScopeApplyApplicationService.approve(applyId, userContext.getUserId());
        return ReqResult.success();
    }

    @RequireResource(OAUTH2_SCOPE_AUDIT_REJECT)
    @Operation(summary = "审核拒绝（项目 scope 保持不变）")
    @PostMapping("/reject/{applyId}")
    public ReqResult<Void> reject(@PathVariable("applyId") Long applyId, @RequestBody OAuth2ScopeApplyRejectReq req) {
        UserContext userContext = getUser();
        String reason = req == null ? null : StringUtils.trimToNull(req.getReason());
        oauth2ScopeApplyApplicationService.reject(applyId, userContext.getUserId(), reason);
        return ReqResult.success();
    }

    /**
     * 补充所属应用信息
     */
    private OAuth2ScopeApplyVo toVo(OAuth2ScopeApplyModel apply, UserProjectModel project) {
        OAuth2ScopeApplyVo vo = new OAuth2ScopeApplyVo();
        vo.setId(apply.getId());
        vo.setProjectId(apply.getProjectId());
        vo.setProjectType(apply.getProjectType() == null ? null : apply.getProjectType().name());
        vo.setApplyUserId(apply.getApplyUserId());
        vo.setOldScopes(OAuth2ScopeRegistry.toList(apply.getOldScopes()));
        vo.setScopes(OAuth2ScopeRegistry.toList(apply.getScopes()));
        vo.setStatus(apply.getStatus() == null ? null : apply.getStatus().name());
        vo.setReviewerId(apply.getReviewerId());
        vo.setRejectReason(apply.getRejectReason());
        vo.setReviewedAt(apply.getReviewedAt());
        vo.setCreated(apply.getCreated());
        if (project != null) {
            vo.setProjectName(project.getName());
            vo.setClientId(project.getOauthClientId());
        }
        return vo;
    }
}
