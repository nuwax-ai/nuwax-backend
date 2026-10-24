package com.xspaceagi.userapp.web.controller;

import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.application.CollectApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import com.xspaceagi.userapp.web.dto.UserProjectCreateReq;
import com.xspaceagi.userapp.web.dto.UserProjectUpdateReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

/**
 * 常规项目入口（仅 NormalProject 类型）
 */
@Tag(name = "Normal project", description = "Normal project APIs")
@RestController
@RequestMapping("/api/normal-project")
@Slf4j
public class NormalProjectController extends BaseController {

    @Resource
    private IUserProjectApplicationService userProjectApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;
    @Resource
    private CollectApplicationService collectApplicationService;
    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @RequireResource(NORMAL_PROJECT_QUERY)
    @Operation(summary = "按ID查询（仅常规项目；id 即项目主键）")
    @GetMapping("/get/{id}")
    public ReqResult<UserProjectDto> get(@PathVariable("id") Long id) {
        try {
            UserContext userContext = getUser();
            requireNormalProject(id);
            // 补齐资料库资源（无目录/无计划表则创建），失败不阻断查询
            UserProjectModel project = userProjectApplicationService.ensureRepoResources(id);
            UserProjectDto dto = toDto(project);
            fillWorkspace(userContext.getUserId(), id, dto);
            dto.setOwner(userContext.getUserId() != null
                    && userContext.getUserId().equals(dto.getCreatorId()));
            return ReqResult.success(dto);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query user project failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(NORMAL_PROJECT_QUERY)
    @Operation(summary = "查询当前用户最近一条开发会话（无则返回 null，仅常规项目）")
    @GetMapping("/conversation/{id}")
    public ReqResult<ConversationDto> conversation(@PathVariable("id") Long id) {
        try {
            UserContext userContext = getUser();
            requireNormalProject(id);
            return ReqResult.success(conversationApplicationService.getLatestConversationByDevTarget(
                    userContext.getUserId(), ProjectTypeEnum.NormalProject.name(), id));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Get or create user project conversation failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(NORMAL_PROJECT_CREATE)
    @Operation(summary = "创建常规项目")
    @PostMapping("/create")
    public ReqResult<UserProjectDto> create(@RequestBody UserProjectCreateReq req) {
        try {
            UserContext userContext = getUser();

            // 空间：不传则默认个人空间
            Long spaceId = req.getSpaceId();
            if (spaceId != null) {
                spacePermissionService.checkSpaceUserPermission(spaceId);
            } else {
                List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userContext.getUserId());
                if (spaces.isEmpty()) {
                    throw new BizException("Invalid user data");
                }
                spaceId = spaces.stream()
                        .filter(s -> s.getType() == Space.Type.Personal)
                        .findFirst().orElse(spaces.get(0)).getId();
            }

            // 名称缺省（目录末级文件夹名/按语言默认）由 createNormal 统一处理

            // 只建项目记录，不默认创建开发会话
            UserProjectModel model = new UserProjectModel();
            model.setCreatorId(userContext.getUserId());
            model.setSpaceId(spaceId);
            model.setName(req.getName());
            model.setDescription(req.getDescription());
            model.setIcon(req.getIcon());
            model.setSandboxId(req.getSandboxId());
            // createNormal 内部完成创建者的工作目录登记（自定义目录或规则生成，同事务）
            UserProjectModel created = userProjectApplicationService.createNormal(model, req.getWorkspacePath());

            return ReqResult.success(toDto(created));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create user project failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(NORMAL_PROJECT_MODIFY)
    @Operation(summary = "更新基本信息（传 null 的字段不更新）")
    @PostMapping("/update")
    public ReqResult<UserProjectDto> update(@RequestBody UserProjectUpdateReq req) {
        try {
            getUser();
            if (req == null || req.getId() == null) {
                throw new IllegalArgumentException("id is required");
            }
            requireNormalProject(req.getId());
            return ReqResult.success(toDto(userProjectApplicationService.updateNormal(req.getId(), req.getName(), req.getDescription(), req.getIcon())));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Update user project failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(NORMAL_PROJECT_DELETE)
    @Operation(summary = "删除常规项目（清理沙箱工作目录按项目沙箱类型；全栈应用走 /api/userapp 删除入口）")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable("id") Long id) {
        try {
            getUser();
            // 权限 + 类型校验（一次查询，沙箱类型判断需要项目行），再反查全部开发会话
            UserProjectModel project = requireNormalProject(id);
            List<ConversationDto> devConversations = conversationApplicationService
                    .getConversationListByDevTarget(ProjectTypeEnum.NormalProject.name(), id);
            userProjectApplicationService.deleteNormal(id);
            // 收藏关系随项目硬删除一并清理（全栈应用为软删除，关系保留无影响）；清理失败不阻断删除，残留关系查询时自然过滤
            try {
                collectApplicationService.clearCollect(Published.TargetType.NormalProject, id);
            } catch (Exception e) {
                log.warn("Failed to clear collect relations for deleted normal project, projectId={}", id, e);
            }

            // 删除云端空间
            Map<Long, ConversationDto> cloudSandboxConversations = new LinkedHashMap<>();
            for (ConversationDto conversation : devConversations) {
                String sandboxServerId = conversation.getSandboxServerId();
                if (StringUtils.isBlank(sandboxServerId) || "-1".equals(sandboxServerId)) {
                    continue;
                }
                try {
                    Long serverId = Long.parseLong(sandboxServerId);
                    if (cloudSandboxConversations.containsKey(serverId)) {
                        continue;
                    }
                    SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(serverId);
                    if (sandboxConfig == null || sandboxConfig.getScope() != SandboxScopeEnum.USER) {
                        // 云端池机器：记录一条代表会话用于定位删除
                        cloudSandboxConversations.put(serverId, conversation);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid sandboxServerId for normal project conversation, projectId={}, cId={}, sandboxServerId={}",
                            id, conversation.getId(), sandboxServerId);
                }
            }
            for (ConversationDto conversation : cloudSandboxConversations.values()) {
                try {
                    agentWorkspaceApplicationService.deleteWorkspace(conversation.getUserId(), conversation.getId());
                    // 云端会话是复用空间，只需要删一次
                    break;
                } catch (Exception e) {
                    log.warn("Failed to delete sandbox workspace for normal project, projectId={}, cId={}",
                            id, conversation.getId(), e);
                }
            }

            // 项目会话随项目删除联动清理（含消息、收藏）；清理失败不阻断项目删除
            try {
                conversationApplicationService.deleteByDevTarget(ProjectTypeEnum.NormalProject.name(), id);
            } catch (Exception e) {
                log.warn("Failed to delete normal project conversations, projectId={}", id, e);
            }
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Delete user project failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    private void fillWorkspace(Long userId, Long projectId, UserProjectDto dto) {
        if (userId == null) {
            return;
        }
        try {
            UserProjectWorkspaceModel binding = userProjectApplicationService
                    .getUserWorkspace(userId, ProjectTypeEnum.NormalProject, projectId);
            if (binding != null) {
                dto.setSandboxId(binding.getSandboxId());
                dto.setFileWorkspacePath(binding.getFileWorkspacePath());
                dto.setAgentWorkspacePath(binding.getAgentWorkspacePath());
            }
        } catch (Exception e) {
            log.warn("Fill normal project workspace failed, projectId={}, userId={}", projectId, userId, e);
        }
    }

    private UserProjectModel checkSpacePermissionByProjectId(Long id) {
        UserProjectModel model = userProjectApplicationService.getById(id);
        if (model != null) {
            spacePermissionService.checkSpaceUserPermission(model.getSpaceId());
        } else {
            throw new IllegalArgumentException("User project does not exist");
        }
        return model;
    }

    /** 空间权限 + 仅常规项目 */
    private UserProjectModel requireNormalProject(Long id) {
        UserProjectModel model = checkSpacePermissionByProjectId(id);
        if (model.getProjectType() != ProjectTypeEnum.NormalProject) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNormalOnly);
        }
        return model;
    }

    private UserProjectDto toDto(UserProjectModel model) {
        if (model == null) {
            return null;
        }
        UserProjectDto dto = new UserProjectDto();
        dto.setDbId(model.getId());
        dto.setTenantId(model.getTenantId());
        dto.setSpaceId(model.getSpaceId());
        dto.setCreatorId(model.getCreatorId());
        dto.setName(model.getName());
        dto.setDescription(model.getDescription());
        dto.setIcon(model.getIcon());
        dto.setProjectType(model.getProjectType());
        // 统一按 项目类型 + projectId 定位项目（常规项目即主键）
        dto.setProjectId(model.getRefId() != null ? model.getRefId() : model.getId());
        dto.setSandboxId(model.getSandboxId());
        dto.setDevAgentId(model.getDevAgentId());
        dto.setRepoSlugId(model.getRepoSlugId());
        dto.setPlanSlugId(model.getPlanSlugId());
        dto.setPinned(Boolean.TRUE.equals(model.getPinned()));
        dto.setArchived(Boolean.TRUE.equals(model.getArchived()));
        dto.setNameDefined(Boolean.TRUE.equals(model.getNameDefined()));
        dto.setSandboxType(model.getSandboxType());
        dto.setModified(model.getModified());
        dto.setCreated(model.getCreated());
        return dto;
    }
}
