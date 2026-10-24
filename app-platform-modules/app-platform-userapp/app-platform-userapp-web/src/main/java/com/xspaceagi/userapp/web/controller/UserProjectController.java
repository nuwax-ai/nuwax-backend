package com.xspaceagi.userapp.web.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

import com.xspaceagi.system.spec.annotation.RequireResource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.agent.core.adapter.application.CollectApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConfigHistoryApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConfigHistoryDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ArchivedFilter;
import com.xspaceagi.agent.core.adapter.dto.CollectedFilter;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectQueryReq;
import com.xspaceagi.userapp.web.dto.UserProjectConversationVo;
import com.xspaceagi.userapp.web.dto.UserProjectConversationsReq;
import com.xspaceagi.userapp.web.dto.UserProjectTabVo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

/**
 * 用户项目统一入口
 */
@Tag(name = "User project", description = "User project unified APIs")
@RestController
@RequestMapping("/api/user-project")
@Slf4j
public class UserProjectController extends BaseController {

    @Resource
    private IUserProjectApplicationService userProjectApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private CollectApplicationService collectApplicationService;
    @Resource
    private UserApplicationService userApplicationService;
    @Resource
    private ConfigHistoryApplicationService configHistoryApplicationService;

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "分页查询（项目列表：默认常规项目+全栈应用+三方应用，附带当前用户在各项目的开发会话")
    @PostMapping("/page-query")
    public ReqResult<SuperPage<UserProjectTabVo>> pageQuery(@RequestBody PageQueryVo<UserProjectQueryReq> req) {
        try {
            UserContext userContext = getUser();
            if (req == null) {
                req = new PageQueryVo<>();
            }
            UserProjectQueryReq filter = req.getQueryFilter();
            if (filter == null) {
                filter = new UserProjectQueryReq();
                req.setQueryFilter(filter);
            }
            UserProjectModel query = toQueryModel(filter);
            if (filter.getSpaceId() != null) {
                spacePermissionService.checkSpaceUserPermission(filter.getSpaceId());
            } else {
                // 不按空间查询时取用户所在全部空间：团队协作下同一空间成员互相可见对方项目
                List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userContext.getUserId());
                if (spaces.isEmpty()) {
                    return ReqResult.success(new SuperPage<>(req.getCurrent(), req.getPageSize(), 0, List.<UserProjectTabVo>of()));
                }
                query.setCreatorId(null);
                query.setSpaceIds(spaces.stream().map(SpaceDto::getId).toList());
            }
            // 类型：默认常规项目+全栈应用+三方应用，前端传 projectTypes 时按传的查；置顶标记由仓储排序置最前
            if (filter.getProjectTypes() == null || filter.getProjectTypes().isEmpty()) {
                query.setProjectTypes(List.of(ProjectTypeEnum.NormalProject, ProjectTypeEnum.UserApp, ProjectTypeEnum.ThirdApp));
            }
            // 归档过滤：all=全部；exclude=排除归档（默认，与历史列表行为一致）；only=仅归档
            query.setArchivedEq(resolveArchivedEq(filter.getArchivedFilter()));
            // 收藏过滤：无收藏直接返回空页，避免空 IN 进查询
            List<Long> collectedIds = resolveCollectedIds(filter.getCollectedFilter(), userContext.getUserId());
            if (collectedIds != null && collectedIds.isEmpty()) {
                return ReqResult.success(new SuperPage<>(req.getCurrent(), req.getPageSize(), 0, List.of()));
            }
            query.setIds(collectedIds);
            SuperPage<UserProjectModel> page = userProjectApplicationService.pageQuery(query, req.getCurrent(), req.getPageSize());
            List<UserProjectTabVo> records = page.getRecords() == null ? List.of()
                    : page.getRecords().stream().map(this::toTabVo).toList();
            fillConversations(userContext.getUserId(), records);
            fillCollected(userContext.getUserId(), records);
            fillOwner(userContext.getUserId(), records);
            return ReqResult.success(new SuperPage<>(page.getCurrent(), page.getSize(), page.getTotal(), records));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Page query user project failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    /** 归档过滤解析：API 三态 all/exclude/only（大小写不敏感）→ 模型 archivedEq 列匹配值（ALL=null 不过滤，ONLY=true，EXCLUDE=false） */
    private Boolean resolveArchivedEq(String archivedFilter) {
        ArchivedFilter parsed = StringUtils.isBlank(archivedFilter)
                ? ArchivedFilter.EXCLUDE
                : ArchivedFilter.parse(archivedFilter);
        return switch (parsed) {
            case ALL -> null;
            case ONLY -> Boolean.TRUE;
            case EXCLUDE -> Boolean.FALSE;
        };
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "项目置顶/取消置顶（id 为 projectId：常规项目=user_project主键，全栈应用=user_app主键；projectType 必传）")
    @PostMapping("/pin/{projectId}")
    public ReqResult<UserProjectDto> pin(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType,
            @RequestParam(value = "pinned", defaultValue = "true") boolean pinned) {
        try {
            getUser();
            ProjectTypeEnum type = requireProjectType(projectType);
            UserProjectModel project = resolveProjectTarget(type, projectId);
            return ReqResult.success(toDto(userProjectApplicationService.updateFlags(project.getId(), pinned, null)));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Pin user project failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "项目归档/取消归档（参数语义同置顶接口）")
    @PostMapping("/archive/{projectId}")
    public ReqResult<UserProjectDto> archive(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType,
            @RequestParam(value = "archived", defaultValue = "true") boolean archived) {
        try {
            getUser();
            ProjectTypeEnum type = requireProjectType(projectType);
            UserProjectModel project = resolveProjectTarget(type, projectId);
            return ReqResult.success(toDto(userProjectApplicationService.updateFlags(project.getId(), null, archived)));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Archive user project failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "项目收藏")
    @PostMapping("/collect/{projectId}")
    public ReqResult<Void> collect(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType) {
        try {
            UserContext userContext = getUser();
            ProjectTypeEnum type = requireCollectProjectType(projectType);
            resolveProjectTarget(type, projectId);
            if (type == ProjectTypeEnum.NormalProject) {
                collectApplicationService.collectNormalProject(userContext.getUserId(), projectId);
            } else if (type == ProjectTypeEnum.ThirdApp) {
                collectApplicationService.collectThirdApp(userContext.getUserId(), projectId);
            } else {
                collectApplicationService.collectUserApp(userContext.getUserId(), projectId);
            }
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Collect user project failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "项目取消收藏")
    @PostMapping("/unCollect/{projectId}")
    public ReqResult<Void> unCollect(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType) {
        try {
            UserContext userContext = getUser();
            ProjectTypeEnum type = requireCollectProjectType(projectType);
            resolveProjectTarget(type, projectId);
            if (type == ProjectTypeEnum.NormalProject) {
                collectApplicationService.unCollectNormalProject(userContext.getUserId(), projectId);
            } else if (type == ProjectTypeEnum.ThirdApp) {
                collectApplicationService.unCollectThirdApp(userContext.getUserId(), projectId);
            } else {
                collectApplicationService.unCollectUserApp(userContext.getUserId(), projectId);
            }
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("UnCollect user project failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "查询应用历史配置信息（三方应用/全栈应用的发布操作流水）")
    @GetMapping("/config/history/list/{projectId}")
    public ReqResult<List<ConfigHistoryDto>> historyList(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType) {
        try {
            ProjectTypeEnum type = requireAppProjectType(projectType);
            // 存在性 + 空间成员权限（与应用设置页 requireAccess 同口径）
            resolveProjectTarget(type, projectId);
            Published.TargetType targetType = type == ProjectTypeEnum.UserApp
                    ? Published.TargetType.UserApp
                    : Published.TargetType.ThirdApp;
            // config_history 的 target_id 与发布流程一致：三方应用即统一表主键、全栈应用即 userapp 主键
            return ReqResult.success(configHistoryApplicationService.queryConfigHistoryList(targetType, projectId));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query app config history failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /** 历史配置查询仅支持应用类项目（三方应用/全栈应用），其余类型走各自模块的同名接口 */
    private ProjectTypeEnum requireAppProjectType(String projectType) {
        ProjectTypeEnum type = requireProjectType(projectType);
        if (type != ProjectTypeEnum.ThirdApp && type != ProjectTypeEnum.UserApp) {
            throw new IllegalArgumentException("Unsupported projectType for app config history: " + projectType);
        }
        return type;
    }

    @RequireResource({USER_APP_QUERY, NORMAL_PROJECT_QUERY, THIRD_APP_QUERY})
    @Operation(summary = "查询项目会话列表（返回所有用户在该项目的会话，附带会话所属用户名）")
    @GetMapping("/conversations/{projectId}")
    public ReqResult<List<UserProjectConversationVo>> conversations(@PathVariable("projectId") Long projectId,
            @RequestParam(value = "projectType") String projectType) {
        try {
            ProjectTypeEnum type = requireProjectType(projectType);
            resolveProjectTarget(type, projectId);
            List<ConversationDto> conversations = conversationApplicationService
                    .getConversationListByDevTarget(type.name(), projectId);
            return ReqResult.success(fillConversationUserNames(conversations));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("List user project conversations failed, projectId={}, projectType={}", projectId, projectType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /** 会话条目转 VO 并批量回填会话所属用户名 */
    private List<UserProjectConversationVo> fillConversationUserNames(List<ConversationDto> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = conversations.stream()
                .map(ConversationDto::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> userNameMap = userIds.isEmpty() ? Map.of()
                : userApplicationService.queryUserListByIds(userIds).stream()
                        .collect(Collectors.toMap(UserDto::getId, UserDto::getUserName, (a, b) -> a));
        return conversations.stream().map(conversation -> {
            UserProjectConversationVo vo = new UserProjectConversationVo();
            BeanUtils.copyProperties(conversation, vo);
            if (conversation.getUserId() != null) {
                vo.setUserName(userNameMap.get(conversation.getUserId()));
            }
            return vo;
        }).toList();
    }

    /**
     * 批量填充当前用户在各项目的开发会话：按项目类型分组，每类型一条 IN 查询，
     * 过滤当前用户后按 devTargetId（即各项目真实 id / refId）归位到项目维度。
     */
    private void fillConversations(Long userId, List<UserProjectTabVo> records) {
        if (records.isEmpty()) {
            return;
        }
        Map<ProjectTypeEnum, List<UserProjectTabVo>> byType = records.stream()
                .collect(Collectors.groupingBy(UserProjectDto::getProjectType));
        for (Map.Entry<ProjectTypeEnum, List<UserProjectTabVo>> entry : byType.entrySet()) {
            List<Long> projectIds = entry.getValue().stream()
                    .map(UserProjectDto::getProjectId)
                    .toList();
            Map<String, List<ConversationDto>> byProject = conversationApplicationService
                    .getConversationListByDevTargets(entry.getKey().name(), projectIds)
                    .stream()
                    .filter(c -> userId.equals(c.getUserId()))
                    .collect(Collectors.groupingBy(ConversationDto::getDevTargetId));
            for (UserProjectTabVo vo : entry.getValue()) {
                vo.setConversations(byProject.getOrDefault(String.valueOf(vo.getProjectId()), List.of()));
            }
        }
    }

    /** 回填当前用户收藏标记：按项目类型分组批量查询（收藏 targetId 与各类型 projectId 同源） */
    private <T extends UserProjectDto> void fillCollected(Long userId, List<T> dtos) {
        if (userId == null || dtos == null || dtos.isEmpty()) {
            return;
        }
        Map<ProjectTypeEnum, Set<Long>> collectedByType = new HashMap<>();
        dtos.stream()
                .filter(dto -> dto.getProjectType() != null)
                .collect(Collectors.groupingBy(UserProjectDto::getProjectType))
                .forEach((type, group) -> {
                    Published.TargetType targetType = toCollectTargetType(type);
                    if (targetType == null) {
                        return;
                    }
                    List<Long> projectIds = group.stream().map(UserProjectDto::getProjectId).toList();
                    collectedByType.put(type, new HashSet<>(
                            collectApplicationService.queryCollectedTargetIds(userId, targetType, projectIds)));
                });
        dtos.forEach(dto -> dto.setCollected(dto.getProjectType() != null
                && collectedByType.getOrDefault(dto.getProjectType(), Set.of()).contains(dto.getProjectId())));
    }

    /** 回填创建者标记：creatorId 即登录用户本人 */
    private <T extends UserProjectDto> void fillOwner(Long userId, List<T> dtos) {
        if (userId == null || dtos == null) {
            return;
        }
        dtos.forEach(dto -> dto.setOwner(userId.equals(dto.getCreatorId())));
    }

    /** 收藏过滤解析：collectedFilter=only 时返回三类收藏对应的统一表主键集合（可能为空集）；否则返回 null 表示不过滤 */
    private List<Long> resolveCollectedIds(String collectedFilter, Long userId) {
        if (userId == null || CollectedFilter.parse(collectedFilter) != CollectedFilter.ONLY) {
            return null;
        }
        // 常规项目/三方应用收藏 targetId 即统一表主键；全栈应用收藏 targetId 为 user_app 主键，需反查统一表主键
        List<Long> normalIds = collectApplicationService.queryAllCollectedTargetIds(userId, Published.TargetType.NormalProject);
        List<Long> thirdAppIds = collectApplicationService.queryAllCollectedTargetIds(userId, Published.TargetType.ThirdApp);
        List<Long> appIds = collectApplicationService.queryAllCollectedTargetIds(userId, Published.TargetType.UserApp);
        if (normalIds.isEmpty() && thirdAppIds.isEmpty() && appIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(normalIds);
        ids.addAll(thirdAppIds);
        userProjectApplicationService.listByRefs(ProjectTypeEnum.UserApp, appIds)
                .forEach(model -> ids.add(model.getId()));
        return ids;
    }

    /** 项目类型 → 收藏关系类型（仅常规项目/全栈应用/三方应用支持收藏） */
    private Published.TargetType toCollectTargetType(ProjectTypeEnum projectType) {
        return switch (projectType) {
            case NormalProject -> Published.TargetType.NormalProject;
            case UserApp -> Published.TargetType.UserApp;
            case ThirdApp -> Published.TargetType.ThirdApp;
            default -> null;
        };
    }

    //@Operation(summary = "查询当前用户在指定项目列表中的开发会话（会话含 devTargetType/devTargetId，可归属到项目）")
    //@PostMapping("/conversations/list")
    public ReqResult<List<ConversationDto>> listConversations(@RequestBody UserProjectConversationsReq req) {
        try {
            UserContext userContext = getUser();
            if (req == null || req.getProjects() == null || req.getProjects().isEmpty()) {
                throw new IllegalArgumentException("projects is required");
            }
            List<ConversationDto> result = new ArrayList<>();
            Set<String> handled = new HashSet<>();
            for (UserProjectConversationsReq.ProjectSelector selector : req.getProjects()) {
                if (selector == null || selector.getProjectId() == null || StringUtils.isBlank(selector.getProjectType())) {
                    throw new IllegalArgumentException("projectId and projectType are required for every project");
                }
                ProjectTypeEnum type = requireProjectType(selector.getProjectType());
                // 去重同一项目
                if (!handled.add(type.name() + ":" + selector.getProjectId())) {
                    continue;
                }
                // 空间权限逐项目校验（fail-closed）
                UserProjectModel project = resolveProject(type, selector.getProjectId());
                if (project == null) {
                    throw new IllegalArgumentException("User project does not exist");
                }
                spacePermissionService.checkSpaceUserPermission(project.getSpaceId());
                // 当前用户在该项目创建的全部会话（每个项目按 id 倒序）
                conversationApplicationService.getConversationListByDevTarget(type.name(), selector.getProjectId())
                        .stream()
                        .filter(c -> userContext.getUserId().equals(c.getUserId()))
                        .forEach(result::add);
            }
            return ReqResult.success(result);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("List user project conversations failed, req={}", req, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /** 项目类型解析（非法直接报错） */
    private ProjectTypeEnum requireProjectType(String projectType) {
        if (StringUtils.isBlank(projectType)) {
            throw new IllegalArgumentException("projectType is required");
        }
        try {
            return ProjectTypeEnum.valueOf(projectType.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid projectType: " + projectType);
        }
    }

    /** 按类型 + 真实项目 id 解析统一登记行：无引用表的类型（常规项目、三方应用）即主键，其余按 ref_id */
    private UserProjectModel resolveProject(ProjectTypeEnum type, Long projectId) {
        return type.hasRefTable()
                ? userProjectApplicationService.getByRef(type, projectId)
                : userProjectApplicationService.getById(projectId);
    }

    /**
     * 项目行解析与空间权限校验：无引用表的类型（常规项目、三方应用）projectId 即统一表主键；
     * 有引用表的类型（全栈应用等）projectId 为业务主键（按 refId 反查统一表行）。
     */
    private UserProjectModel resolveProjectTarget(ProjectTypeEnum type, Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        UserProjectModel model = resolveProject(type, projectId);
        if (model == null) {
            throw new IllegalArgumentException("User project does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(model.getSpaceId());
        return model;
    }

    /** 收藏仅支持常规项目、全栈应用与三方应用，其余类型直接报错 */
    private ProjectTypeEnum requireCollectProjectType(String projectType) {
        ProjectTypeEnum type = requireProjectType(projectType);
        if (type != ProjectTypeEnum.NormalProject && type != ProjectTypeEnum.UserApp && type != ProjectTypeEnum.ThirdApp) {
            throw new IllegalArgumentException("Unsupported projectType for collect: " + projectType);
        }
        return type;
    }

    private UserProjectModel toQueryModel(UserProjectQueryReq req) {
        UserProjectModel query = new UserProjectModel();
        if (req != null) {
            query.setSpaceId(req.getSpaceId());
            query.setCreatorId(req.getCreatorId());
            query.setProjectTypes(req.getProjectTypes());
            query.setName(req.getName());
        }
        return query;
    }

    private UserProjectDto toDto(UserProjectModel model) {
        return fillDto(model, new UserProjectDto(), null);
    }

    private UserProjectTabVo toTabVo(UserProjectModel model) {
        return fillDto(model, new UserProjectTabVo(), null);
    }

    private <T extends UserProjectDto> T fillDto(UserProjectModel model, T dto, Long conversationId) {
        if (model == null) {
            return null;
        }
        dto.setDbId(model.getId());
        dto.setTenantId(model.getTenantId());
        dto.setSpaceId(model.getSpaceId());
        dto.setCreatorId(model.getCreatorId());
        dto.setName(model.getName());
        dto.setDescription(model.getDescription());
        dto.setIcon(model.getIcon());
        dto.setProjectType(model.getProjectType());
        // 统一按 项目类型 + refId 定位项目
        dto.setProjectId(model.getRefId() != null ? model.getRefId() : model.getId());
        dto.setSandboxId(model.getSandboxId());
        dto.setDevAgentId(model.getDevAgentId());
        dto.setRepoSlugId(model.getRepoSlugId());
        dto.setPlanSlugId(model.getPlanSlugId());
        // 密钥不下发：仅暴露 clientId 与接入地址（与 RPC convertToDto 同口径）
        dto.setOauthClientId(model.getOauthClientId());
        dto.setHomepageUrl(model.getHomepageUrl());
        dto.setRedirectUri(model.getRedirectUri());
        dto.setRedirectUris(model.getRedirectUris());
        dto.setOauthEnabled(model.getOauthEnabled());
        dto.setPublishStatus(model.getPublishStatus());
        dto.setPinned(Boolean.TRUE.equals(model.getPinned()));
        dto.setArchived(Boolean.TRUE.equals(model.getArchived()));
        dto.setNameDefined(Boolean.TRUE.equals(model.getNameDefined()));
        dto.setSandboxType(model.getSandboxType());
        dto.setModified(model.getModified());
        dto.setCreated(model.getCreated());
        dto.setConversationId(conversationId);
        return dto;
    }
}
