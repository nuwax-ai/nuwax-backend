package com.xspaceagi.userapp.web.controller;

import com.xspaceagi.sandbox.SandboxUtils;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthCredential;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthSettingResult;
import com.xspaceagi.userapp.application.service.IOAuth2ScopeApplyApplicationService;
import com.xspaceagi.userapp.application.service.IThirdAppOAuth2ApplicationService;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.OAuth2ScopeApplyModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.dto.OAuth2ScopeApplyStatus;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import com.xspaceagi.userapp.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 三方应用「设置 - OAuth2 认证信息」卡片对应接口。
 * <p>
 * 面向平台内的项目设置页，全部需登录态 + 空间权限；密钥明文仅通过 /secret 单独获取并留审计日志。
 * <p>
 * 另含三方应用自身的增改入口 {@code POST /create} 与 {@code POST /update}：三方应用没有独立源表，
 * 创建即落一行 {@code user_project}（project_type=ThirdApp），基本信息也存在该行上，故并入本控制器。
 */
@Tag(name = "Third app OAuth2", description = "Third-party app OAuth2 integration settings")
@RestController
@RequestMapping("/api/user-project/oauth2")
@Slf4j
public class ThirdAppOAuth2Controller extends BaseController {

    @Resource
    private IThirdAppOAuth2ApplicationService thirdAppOAuth2ApplicationService;
    @Resource
    private IOAuth2ScopeApplyApplicationService oauth2ScopeApplyApplicationService;
    @Resource
    private IUserProjectApplicationService userProjectApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Operation(summary = "创建三方应用（自动生成 OAuth2 凭证；明文密钥仍需调 /secret 获取）")
    @PostMapping("/create")
    public ReqResult<ThirdAppOAuthSettingVo> create(@RequestBody ThirdAppCreateReq req) {
        try {
            UserContext userContext = getUser();
            if (req == null) {
                throw new IllegalArgumentException("req is required");
            }
            Long spaceId = resolveSpaceId(req.getSpaceId(), userContext.getUserId());
            UserProjectModel model = new UserProjectModel();
            model.setCreatorId(userContext.getUserId());
            model.setSpaceId(spaceId);
            model.setName(StringUtils.trimToNull(req.getName()));
            model.setDescription(StringUtils.trimToNull(req.getDescription()));
            model.setIcon(StringUtils.trimToNull(req.getIcon()));
            UserProjectModel created = userProjectApplicationService.createThirdApp(model);
            // 没有凭证的三方应用不可用：建行即生成，与 GET /setting 的自动生成语义一致
            UserProjectModel project = thirdAppOAuth2ApplicationService.ensureCredential(created.getId());
            log.info("Third app created, projectId={}, spaceId={}, clientId={}, userId={}", project.getId(), spaceId,
                    project.getOauthClientId(), userContext.getUserId());
            return ReqResult.success(toVo(project));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create third app failed, req={}", req, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "更新三方应用基本信息（名称/描述/图标）")
    @PostMapping("/update")
    public ReqResult<ThirdAppOAuthSettingVo> update(@RequestBody ThirdAppUpdateReq req) {
        try {
            getUser();
            if (req == null || req.getProjectId() == null) {
                throw new IllegalArgumentException("projectId is required");
            }
            requireAccess(req.getProjectId());
            UserProjectModel project = userProjectApplicationService.updateThirdApp(req.getProjectId(),
                    trimPreserveNull(req.getName()), trimPreserveNull(req.getDescription()),
                    trimPreserveNull(req.getIcon()));
            return ReqResult.success(toVo(project));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Update third app failed, req={}", req, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "查询 OAuth2 认证信息（首次访问自动生成凭证，不含密钥明文）")
    @GetMapping("/setting/{projectId}")
    public ReqResult<ThirdAppOAuthSettingVo> getSetting(@PathVariable("projectId") Long projectId, @RequestParam(required = false) ProjectTypeEnum projectType) {
        try {
            projectId = exchangeToPK(projectId, projectType);
            requireAccess(projectId);
            UserProjectModel project = thirdAppOAuth2ApplicationService.ensureCredential(projectId);
            return ReqResult.success(toVo(project));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query third app oauth2 setting failed, projectId={}", projectId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    private Long exchangeToPK(Long projectId, ProjectTypeEnum projectType) {
        if (projectType == ProjectTypeEnum.UserApp) {
            UserProjectModel byRef = userProjectApplicationService.getByRef(projectType, projectId);
            if (byRef == null) {
                throw new IllegalArgumentException("Invalid projectId");
            }
            // 切换为主键
            projectId = byRef.getId();
        }
        return projectId;
    }

    @Operation(summary = "查询三方应用信息（仅读，不自动生成凭证）")
    @GetMapping("/info/{projectId}")
    public ReqResult<ThirdAppInfoVo> getInfo(@PathVariable("projectId") Long projectId) {
        try {
            getUser();
            // 与 /setting 不同：信息查询是无副作用的只读操作，因此不走 ensureCredential
            UserProjectModel project = requireAccess(projectId);
            return ReqResult.success(toInfoVo(project));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query third app info failed, projectId={}", projectId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "查看 Client Secret 明文（审计留痕）")
    @GetMapping("/secret/{projectId}")
    public ReqResult<String> getClientSecret(@PathVariable("projectId") Long projectId, ProjectTypeEnum projectType) {
        try {
            UserContext userContext = getUser();
            projectId = exchangeToPK(projectId, projectType);
            requireAccess(projectId);
            UserProjectModel project = thirdAppOAuth2ApplicationService.ensureCredential(projectId);
            String secret = thirdAppOAuth2ApplicationService.revealClientSecret(project);
            // 审计：密钥明文被查看（日志不含密钥本身）
            log.info("Third app oauth2 client secret viewed, projectId={}, clientId={}, userId={}", projectId,
                    project.getOauthClientId(), userContext.getUserId());
            return ReqResult.success(secret);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query third app oauth2 client secret failed, projectId={}", projectId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "保存主页地址、主回调地址，对补充回调地址做显式增删，并提交 scope 变更申请"
            + "（homepageUrl/redirectUri 留空表示不修改；addRedirectUris/removeRedirectUris 为 null 表示该动作不执行；"
            + "沙箱调用只能删自己添加的条目，删不动的回报在 skippedRedirectUriRemovals 里，其余改动照常生效；"
            + "scopes 提交后待管理员审核，通过前项目按原范围生效）")
    @PostMapping("/setting/save")
    public ReqResult<ThirdAppOAuthSettingVo> saveSetting(@RequestBody ThirdAppOAuthSettingReq req,
            HttpServletRequest request) {
        try {
            UserContext userContext = getUser();
            if (req == null || req.getProjectId() == null) {
                throw new IllegalArgumentException("projectId is required");
            }
            req.setProjectId(exchangeToPK(req.getProjectId(), req.getProjectType()));
            UserProjectModel project = requireAccess(req.getProjectId());
            // 写入来源在这里判定后往下传（业务层不感知传输方式）：沙箱里的 AI 只能维护自己添加的回调地址，
            // 且主地址一律不让它写（库中为空时也不让）。判据放在服务端是因为「不能删别人的」这条规则
            // 防不住调用方绕过前端直接发请求。
            RedirectUriEntry.Source source = SandboxUtils.isSandboxRequest(request)
                    ? RedirectUriEntry.Source.Sandbox : RedirectUriEntry.Source.Manual;
            ThirdAppOAuthSettingResult result = thirdAppOAuth2ApplicationService.saveSetting(project,
                    StringUtils.trimToNull(req.getHomepageUrl()), StringUtils.trimToNull(req.getRedirectUri()),
                    req.getAddRedirectUris(), req.getRemoveRedirectUris(), req.getScopes(),
                    userContext.getUserId(), source);
            return ReqResult.success(toVo(result.project(), result.skippedRedirectUriRemovals()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Save third app oauth2 setting failed, req={}", req, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "平台支持的 OAuth2 scope 全集（设置页勾选）")
    @GetMapping("/scopes")
    public ReqResult<List<ThirdAppOAuthScopeVo>> listScopes() {
        try {
            getUser();
            List<ThirdAppOAuthScopeVo> scopes = OAuth2ScopeRegistry.supportedScopeMetas().stream()
                    .map(meta -> new ThirdAppOAuthScopeVo(meta.scope(), meta.description(), meta.sensitive()))
                    .toList();
            return ReqResult.success(scopes);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("List oauth2 scopes failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "重新生成凭证（旧凭证立即失效，明文密钥仅本次返回）")
    @PostMapping("/credential/regenerate/{projectId}")
    public ReqResult<ThirdAppOAuthCredentialVo> regenerateCredential(@PathVariable("projectId") Long projectId, ProjectTypeEnum projectType) {
        try {
            UserContext userContext = getUser();
            projectId = exchangeToPK(projectId, projectType);
            requireAccess(projectId);
            ThirdAppOAuthCredential credential = thirdAppOAuth2ApplicationService.regenerateCredential(projectId);
            log.info("Third app oauth2 credential regenerated by user, projectId={}, clientId={}, userId={}", projectId,
                    credential.clientId(), userContext.getUserId());
            ThirdAppOAuthCredentialVo vo = new ThirdAppOAuthCredentialVo();
            vo.setClientId(credential.clientId());
            vo.setClientSecret(credential.clientSecret());
            return ReqResult.success(vo);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Regenerate third app oauth2 credential failed, projectId={}", projectId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "删除三方应用（凭证随行吊销：已签发授权码与令牌立即失效）")
    @PostMapping("/delete/{projectId}")
    public ReqResult<Void> delete(@PathVariable("projectId") Long projectId) {
        try {
            UserContext userContext = getUser();
            requireAccess(projectId);
            userProjectApplicationService.deleteThirdApp(projectId);
            log.info("Third app deleted, projectId={}, userId={}", projectId, userContext.getUserId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Delete third app failed, projectId={}", projectId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 目标行必须可作接入应用（ThirdApp/UserApp，见 {@code requireThirdApp}），
     * 且当前用户对其所在空间有访问权限（fail-closed）。返回该行供调用方复用，避免二次查询。
     */
    private UserProjectModel requireAccess(Long projectId) {
        UserProjectModel project = thirdAppOAuth2ApplicationService.requireThirdApp(projectId);
        spacePermissionService.checkSpaceUserPermission(project.getSpaceId());
        return project;
    }

    /**
     * 更新语义用的空值处理：null 表示不更新；非 null 一律 trim 后写入。
     * <p>
     * 与 {@code saveSetting} 的 trimToNull 不同 —— 后者把空串也折叠成「不修改」，
     * 导致字段无法清空；这里保留空串，使描述/图标可以显式清空。
     */
    private static String trimPreserveNull(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 空间：显式传入需校验成员权限，未传入取个人空间（与常规项目创建同款兜底）
     */
    private Long resolveSpaceId(Long spaceId, Long userId) {
        if (spaceId != null) {
            spacePermissionService.checkSpaceUserPermission(spaceId);
            return spaceId;
        }
        List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userId);
        if (spaces.isEmpty()) {
            throw new BizException("Invalid user data");
        }
        return spaces.stream()
                .filter(s -> s.getType() == Space.Type.Personal)
                .findFirst().orElse(spaces.get(0)).getId();
    }

    private ThirdAppInfoVo toInfoVo(UserProjectModel project) {
        ThirdAppInfoVo vo = new ThirdAppInfoVo();
        vo.setProjectId(project.getId());
        vo.setProjectType(project.getProjectType() == null ? null : project.getProjectType().name());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setIcon(project.getIcon());
        vo.setSpaceId(project.getSpaceId());
        vo.setCreatorId(project.getCreatorId());
        vo.setHomepageUrl(project.getHomepageUrl());
        vo.setRedirectUri(project.getRedirectUri());
        vo.setRedirectUris(supplementaryRedirectUris(project));
        vo.setClientId(project.getOauthClientId());
        vo.setHasClientSecret(StringUtils.isNotBlank(project.getOauthClientSecret()));
        vo.setScopes(thirdAppOAuth2ApplicationService.resolveScopes(project));
        vo.setEnabled(Boolean.TRUE.equals(project.getOauthEnabled()));
        vo.setPublishStatus(project.getPublishStatus() == null ? null : project.getPublishStatus().name());
        vo.setCreated(project.getCreated());
        vo.setModified(project.getModified());
        return vo;
    }

    private ThirdAppOAuthSettingVo toVo(UserProjectModel project) {
        return toVo(project, null);
    }

    /**
     * @param skippedRedirectUriRemovals 本次保存里被跳过的删除项，只有 {@code /setting/save} 有值；
     *                                   其余读接口传 null，前端据此区分「没跳过」与「这不是保存的响应」
     */
    private ThirdAppOAuthSettingVo toVo(UserProjectModel project, List<String> skippedRedirectUriRemovals) {
        ThirdAppOAuthSettingVo vo = new ThirdAppOAuthSettingVo();
        vo.setProjectId(project.getId());
        vo.setClientId(project.getOauthClientId());
        vo.setHasClientSecret(StringUtils.isNotBlank(project.getOauthClientSecret()));
        vo.setHomepageUrl(project.getHomepageUrl());
        vo.setRedirectUri(project.getRedirectUri());
        vo.setRedirectUris(supplementaryRedirectUris(project));
        vo.setSkippedRedirectUriRemovals(skippedRedirectUriRemovals);
        vo.setScopes(thirdAppOAuth2ApplicationService.resolveScopes(project));
        vo.setEnabled(Boolean.TRUE.equals(project.getOauthEnabled()));
        fillScopeApply(project.getId(), vo);
        return vo;
    }

    /**
     * 补充回调地址回显：未配置返回空数组而不是 null，前端不必区分「没有」与「没取到」。
     * 主地址仍走 {@code redirectUri} 字段，生效集合由前端取并集。
     * <p>
     * 条目连来源与创建人一起下发（不做有损扁平化）：前端要靠 {@code source} 决定每行的删除入口
     * 是否可用，靠 {@code createdBy}/{@code createdAt} 说明这条是谁什么时候加的。
     */
    private static List<RedirectUriEntry> supplementaryRedirectUris(UserProjectModel project) {
        List<RedirectUriEntry> entries = project.getRedirectUris();
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        // 过滤 null 元素：一行脏数据不该让整个设置页 500
        return entries.stream().filter(Objects::nonNull).toList();
    }

    /**
     * 填充最近一条 scope 变更申请的展示信息：待审核给目标范围、被拒给原因（设置页据此提示审核进度）。
     */
    private void fillScopeApply(Long projectId, ThirdAppOAuthSettingVo vo) {
        OAuth2ScopeApplyModel latest = oauth2ScopeApplyApplicationService.getLatestByProjectId(projectId);
        if (latest == null || latest.getStatus() == null) {
            return;
        }
        vo.setScopeApplyStatus(latest.getStatus().name());
        if (latest.getStatus() == OAuth2ScopeApplyStatus.Pending) {
            // 纯拆分（空串=申请清空回落默认，解析为空数组，与保存接口入参语义对称）
            vo.setPendingScopes(OAuth2ScopeRegistry.toList(latest.getScopes()));
        } else if (latest.getStatus() == OAuth2ScopeApplyStatus.Rejected) {
            vo.setScopeRejectReason(latest.getRejectReason());
        }
    }
}
