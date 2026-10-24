package com.xspaceagi.userapp.web.controller;

import java.util.List;

import com.xspaceagi.system.spec.annotation.RequireResource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.agent.core.adapter.application.CollectApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.IUserProjectRpcService;
import com.xspaceagi.userapp.sdk.dto.UserAppCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectWorkspaceDto;
import com.xspaceagi.userapp.application.service.IUserPrivateServerApplicationService;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.web.dto.UserAppDeployTargetReq;
import com.xspaceagi.userapp.sdk.dto.UserAppQueryReq;
import com.xspaceagi.userapp.sdk.dto.UserAppStage;
import com.xspaceagi.userapp.application.util.UserAppDbCredentialGenerator;
import com.xspaceagi.userapp.web.dto.UserAppDbCredentialReq;
import com.xspaceagi.userapp.web.dto.UserAppDbCredentialVo;
import com.xspaceagi.userapp.web.dto.UserAppCreateReq;
import com.xspaceagi.userapp.web.dto.UserAppUpdateReq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "User app", description = "User app project APIs")
@RestController
@RequestMapping("/api/userapp")
@Slf4j
public class UserAppController extends BaseController {

    @Resource
    private IUserAppRpcService userAppRpcService;
    @Resource
    private IUserProjectRpcService userProjectRpcService;
    @Resource
    private IUserPrivateServerApplicationService userPrivateServerApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource

    private ConversationApplicationService conversationApplicationService;
    @Resource
    private CollectApplicationService collectApplicationService;

    @RequireResource(USER_APP_CREATE)
    @Operation(summary = "创建应用（后台直接创建开发会话）")
    @PostMapping("/create")
    public ReqResult<UserAppDto> create(@RequestBody UserAppCreateReq req) {
        try {
            UserContext userContext = getUser();
            Long userId = userContext.getUserId();

            // 空间：不传则默认个人空间
            Long spaceId = req.getSpaceId();
            if (spaceId != null) {
                spacePermissionService.checkSpaceUserPermission(spaceId);
            } else {
                List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userId);
                if (spaces.isEmpty()) {
                    throw new BizException("Invalid user data");
                }
                spaceId = spaces.stream()
                        .filter(s -> s.getType() == Space.Type.Personal)
                        .findFirst().orElse(spaces.get(0)).getId();
            }

            // 开发智能体由前端指定（必传）：创建项目只登记，不再默认创建开发会话
            if (req.getDevAgentId() == null) {
                throw new BizException("devAgentId is required");
            }

            UserAppCreateDto createDto = new UserAppCreateDto();
            createDto.setCreatorId(userId);
            createDto.setSpaceId(spaceId);
            createDto.setName(req.getName());
            createDto.setDescription(req.getDescription());
            createDto.setIcon(req.getIcon());
            createDto.setSandboxId(req.getSandboxId());
            createDto.setWorkspacePath(req.getWorkspacePath());
            createDto.setDevAgentId(req.getDevAgentId());
            // TODO 本期暂不支持全栈应用自定义工作目录：放开时删除下面这个报错即可（create 内部已支持绑定）
            if (StringUtils.isNotBlank(req.getWorkspacePath())) {
                throw new BizException("Custom workspace directory is not supported for full-stack apps");
            }
            UserAppDto project = userAppRpcService.create(createDto);

            // 不默认创建开发会话
            UserAppDto userAppDto = userAppRpcService.queryById(project.getId());
            return ReqResult.success(userAppDto);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create userapp project failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询当前用户最近一条开发会话（无则返回 null）")
    @GetMapping("/conversation/{id}")
    public ReqResult<ConversationDto> conversation(@PathVariable("id") Long id) {
        try {
            UserContext userContext = getUser();
            checkSpacePermissionByProjectId(id);
            return ReqResult.success(conversationApplicationService.getLatestConversationByDevTarget(
                    userContext.getUserId(), ProjectTypeEnum.UserApp.name(), id));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Get userapp conversation failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "按应用ID查询（主键 id 即 app_id）")
    @GetMapping("/get/{id}")
    public ReqResult<UserAppDto> get(@PathVariable("id") Long id) {
        try {
            UserContext userContext = getUser();
            // 权限校验后补齐资料库资源（无目录/无计划表则创建，存量项目自动补齐），失败不阻断查询
            UserAppDto dto = userAppRpcService.queryById(id);
            if (dto == null) {
                throw new IllegalArgumentException("User app does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
            dto = userAppRpcService.ensureRepoResources(id);
            fillWorkspace(userContext.getUserId(), id, dto);
            dto.setOwner(userContext.getUserId() != null
                    && userContext.getUserId().equals(dto.getCreatorId()));
            return ReqResult.success(dto);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp project failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "按开发会话ID查询")
    @GetMapping("/get-by-conversation/{conversationId}")
    public ReqResult<UserAppDto> getByConversation(@PathVariable("conversationId") Long conversationId) {
        try {
            getUser();
            // 项目不再冗余会话ID：经会话的 dev_target 反查（项目查询仍走租户隔离）
            ConversationDto conversation = conversationApplicationService.getConversationByCid(conversationId);
            if (conversation == null || !ProjectTypeEnum.UserApp.name().equals(conversation.getDevTargetType())
                    || StringUtils.isBlank(conversation.getDevTargetId())) {
                throw new IllegalArgumentException("User app does not exist");
            }
            Long appId;
            try {
                appId = Long.parseLong(conversation.getDevTargetId());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("User app does not exist");
            }
            UserAppDto dto = userAppRpcService.queryById(appId);
            if (dto != null) {
                spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
            }
            return ReqResult.success(dto);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp project by conversation failed, conversationId={}", conversationId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_MODIFY)
    @Operation(summary = "更新基本信息（传 null 的字段不更新）")
    @PostMapping("/update")
    public ReqResult<Void> update(@RequestBody UserAppUpdateReq req) {
        try {
            checkSpacePermissionByProjectId(req.getId());
            userAppRpcService.updateBasicInfo(req.getId(), req.getName(), req.getDescription(),
                    req.getIcon(), req.getCoverImg(), getUser());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Update userapp project failed, id={}", req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "分页查询")
    @PostMapping("/page-query")
    public ReqResult<SuperPage<UserAppDto>> pageQuery(@RequestBody PageQueryVo<UserAppQueryReq> req) {
        try {
            UserContext userContext = getUser();
            if (req == null) {
                req = new PageQueryVo<>();
            }
            UserAppQueryReq filter = req.getQueryFilter();
            if (filter == null) {
                filter = new UserAppQueryReq();
                req.setQueryFilter(filter);
            }
            if (filter.getSpaceId() != null) {
                spacePermissionService.checkSpaceUserPermission(filter.getSpaceId());
            } else {
                // 不按空间查询时仅限本人应用，避免跨用户
                filter.setCreatorId(userContext.getUserId());
            }
            return ReqResult.success(userAppRpcService.pageQuery(req));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Page query userapp project failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DELETE)
    @Operation(summary = "删除应用（标记预删除：立即不可见、代理404；7天后由统一调度任务物理删除并清理沙箱工作目录）")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable("id") Long id) {
        try {
            checkSpacePermissionByProjectId(id);
            // 工作目录清理与物理删除延后到调度任务
            userAppRpcService.delete(id, getUser());
            // 收藏关系随统一登记表注销一并清理；清理失败不阻断删除，残留关系查询时经统一表反查自然过滤
            try {
                collectApplicationService.clearCollect(Published.TargetType.UserApp, id);
            } catch (Exception e) {
                log.warn("Failed to clear collect relations for deleted userapp, appId={}", id, e);
            }
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Delete userapp project failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_MODIFY)
    @Operation(summary = "设置部署服务器（platform=平台沙箱；private=私服）")
    @PostMapping("/deploy-target")
    public ReqResult<Void> setDeployTarget(@RequestBody UserAppDeployTargetReq req) {
        try {
            getUser();
            if (req == null || req.getAppId() == null) {
                throw new IllegalArgumentException("appId is required");
            }
            UserAppDeployType deployType = UserAppDeployType.fromValue(req.getDeployType());
            if (deployType == null) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非法的部署目标类型: " + req.getDeployType());
            }
            checkSpacePermissionByProjectId(req.getAppId());
            userPrivateServerApplicationService.setAppDeployTarget(req.getAppId(), deployType,
                    req.getDeployServerId(), getUser().getUserId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Set userapp deploy target failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_MODIFY)
    @Operation(summary = "保存数据库账号密码（同步重置沙箱数据库密码，下游结果返回给前端）")
    @PostMapping("/save-db-credential")
    public ReqResult<String> saveDbCredential(@RequestBody UserAppDbCredentialReq req) {
        try {
            checkSpacePermissionByProjectId(req.getId());
            boolean devEnv = requireDevEnv(req.getEnv());
            return ReqResult.success(userAppRpcService.saveDbCredential(req.getId(), devEnv,
                    req.getUsername(), req.getPassword(), getUser()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Save userapp db credential failed, id={}", req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询数据库账号密码（解密返回明文，未设置返回 null）")
    @GetMapping("/db-credential/{id}")
    public ReqResult<UserAppDbCredentialVo> getDbCredential(@PathVariable("id") Long id, @RequestParam("env") String env) {
        try {
            Long userId = getUser().getUserId();
            checkSpacePermissionByProjectId(id);
            boolean devEnv = requireDevEnv(env);
            UserAppDto project = userAppRpcService.queryById(id);
            UserAppDbCredentialVo vo = new UserAppDbCredentialVo();
            vo.setUsername(devEnv ? project.getDevDbUsername() : project.getProdDbUsername());
            vo.setPassword(userAppRpcService.getDbPassword(id, devEnv));
            // 审计：凭证明文被查看（日志不含密码本身）
            log.info("Userapp db credential viewed, id={}, devEnv={}, userId={}", id, devEnv, userId);
            return ReqResult.success(vo);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp db credential failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    private static boolean requireDevEnv(String env) {
        if (StringUtils.isBlank(env)) {
            return true;
        }
        UserAppStage stage = UserAppStage.fromValue(env);
        if (stage == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非法的应用环境: " + env);
        }
        return stage == UserAppStage.DEV;
    }

    @RequireResource(USER_APP_MODIFY)
    @Operation(summary = "随机生成数据库账号或密码（type=username/password，仅生成返回不落库，保存另调 save-db-credential）")
    @GetMapping("/gen-db-credential/{id}")
    public ReqResult<String> genDbCredential(@PathVariable("id") Long id,
            @RequestParam("type") String type) {
        try {
            getUser();
            checkSpacePermissionByProjectId(id);
            if ("username".equalsIgnoreCase(type)) {
                return ReqResult.success(UserAppDbCredentialGenerator.randomUsername());
            }
            if ("password".equalsIgnoreCase(type)) {
                return ReqResult.success(UserAppDbCredentialGenerator.randomPassword());
            }
            throw new IllegalArgumentException("type must be username or password");
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Generate userapp db credential failed, id={}, type={}", id, type, e);
            return ReqResult.error(e.getMessage());
        }
    }

    private void fillWorkspace(Long userId, Long appId, UserAppDto dto) {
        if (userId == null) {
            return;
        }
        try {
            UserProjectWorkspaceDto binding = userProjectRpcService
                    .getUserWorkspace(userId, ProjectTypeEnum.UserApp, appId);
            if (binding != null) {
                dto.setSandboxId(binding.getSandboxId());
                dto.setFileWorkspacePath(binding.getFileWorkspacePath());
                dto.setAgentWorkspacePath(binding.getAgentWorkspacePath());
            }
        } catch (Exception e) {
            log.warn("Fill userapp workspace failed, appId={}, userId={}", appId, userId, e);
        }
    }

    /**
     * 空间权限校验，返回项目数据（调用方可复用，避免重复查询）。
     */
    private UserAppDto checkSpacePermissionByProjectId(Long id) {
        UserAppDto dto = userAppRpcService.queryById(id);
        if (dto != null) {
            spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
        } else {
            throw new IllegalArgumentException("User app does not exist");
        }
        return dto;
    }
}
