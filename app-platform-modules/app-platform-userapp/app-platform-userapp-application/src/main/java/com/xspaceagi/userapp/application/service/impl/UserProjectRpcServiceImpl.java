package com.xspaceagi.userapp.application.service.impl;

import java.util.List;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.sdk.IUserProjectRpcService;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.UserProjectCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectWorkspaceDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectQueryReq;
import com.xspaceagi.userapp.sdk.dto.UserProjectUpsertDto;

import jakarta.annotation.Resource;

@Service
public class UserProjectRpcServiceImpl implements IUserProjectRpcService {

    @Resource
    private IUserProjectApplicationService userProjectApplicationService;

    @Override
    public UserProjectDto register(UserProjectUpsertDto upsertDto) {
        if (upsertDto == null) {
            throw new IllegalArgumentException("upsertDto is required");
        }
        UserProjectModel model = userProjectApplicationService.register(upsertDto.getProjectType(),
                upsertDto.getRefId(), upsertDto.getName(), upsertDto.getDescription(), upsertDto.getIcon(),
                upsertDto.getSpaceId(), upsertDto.getCreatorId(), upsertDto.getSandboxId(), upsertDto.getDevAgentId(),
                upsertDto.getNameDefined());
        return convertToDto(model);
    }

    @Override
    public UserProjectDto createNormal(UserProjectCreateDto createDto) {
        if (createDto == null) {
            throw new IllegalArgumentException("createDto is required");
        }
        UserProjectModel model = new UserProjectModel();
        model.setCreatorId(createDto.getCreatorId());
        model.setSpaceId(createDto.getSpaceId());
        model.setName(createDto.getName());
        model.setDescription(createDto.getDescription());
        model.setIcon(createDto.getIcon());
        model.setSandboxId(createDto.getSandboxId());
        return convertToDto(userProjectApplicationService.createNormal(model, createDto.getWorkspacePath()));
    }

    @Override
    public void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon) {
        userProjectApplicationService.syncBasicInfo(projectType, refId, name, description, icon);
    }

    @Override
    public void unregister(ProjectTypeEnum projectType, Long refId) {
        userProjectApplicationService.unregister(projectType, refId);
    }

    @Override
    public UserProjectWorkspaceDto getUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId) {
        return convertWorkspaceDto(userProjectApplicationService.getUserWorkspace(userId, projectType, projectId));
    }

    @Override
    public UserProjectWorkspaceDto ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId) {
        return convertWorkspaceDto(userProjectApplicationService.ensureUserWorkspace(userId, projectType, projectId));
    }

    @Override
    public String validateConversationWorkspace(Long userId, Long projectId, Long sandboxId, String workspacePath) {
        return userProjectApplicationService.validateConversationWorkspace(userId, projectId, sandboxId, workspacePath);
    }


    private UserProjectWorkspaceDto convertWorkspaceDto(UserProjectWorkspaceModel model) {
        if (model == null) {
            return null;
        }
        UserProjectWorkspaceDto dto = new UserProjectWorkspaceDto();
        dto.setUserId(model.getUserId());
        dto.setProjectType(model.getProjectType());
        dto.setProjectId(model.getProjectId());
        dto.setFileWorkspacePath(model.getFileWorkspacePath());
        dto.setAgentWorkspacePath(model.getAgentWorkspacePath());
        dto.setSandboxId(model.getSandboxId());
        return dto;
    }

    @Override
    public UserProjectDto queryById(Long id) {
        return convertToDto(userProjectApplicationService.getById(id));
    }

    @Override
    public UserProjectDto queryByProjectId(ProjectTypeEnum projectType, Long projectId) {
        return convertToDto(userProjectApplicationService.queryByProjectId(projectType, projectId));
    }

    @Override
    public UserProjectDto queryByRef(ProjectTypeEnum projectType, Long refId) {
        return convertToDto(userProjectApplicationService.getByRef(projectType, refId));
    }

    @Override
    public SuperPage<UserProjectDto> pageQuery(PageQueryVo<UserProjectQueryReq> pageQueryVo) {
        PageQueryVo<UserProjectQueryReq> vo = pageQueryVo == null ? new PageQueryVo<>() : pageQueryVo;
        SuperPage<UserProjectModel> page = userProjectApplicationService.pageQuery(
                toQueryModel(vo.getQueryFilter()), vo.getCurrent(), vo.getPageSize());
        List<UserProjectDto> records = page.getRecords() == null ? List.of()
                : page.getRecords().stream().map(this::convertToDto).collect(Collectors.toList());
        return new SuperPage<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
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

    private UserProjectDto convertToDto(UserProjectModel model) {
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
        // refId 统一承载真实项目 id：常规项目无引用表（refId 空）取统一表主键
        dto.setProjectId(model.getRefId() != null ? model.getRefId() : model.getId());
        dto.setSandboxId(model.getSandboxId());
        dto.setDevAgentId(model.getDevAgentId());
        dto.setRepoSlugId(model.getRepoSlugId());
        dto.setPlanSlugId(model.getPlanSlugId());
        // 密钥不下发：仅暴露 clientId 与接入地址
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
        return dto;
    }

    @Override
    public void checkThirdAppPublishPrecondition(Long projectId) {
        userProjectApplicationService.checkThirdAppPublishPrecondition(projectId);
    }

    @Override
    public void updateThirdAppPublishStatus(Long projectId, UserAppPublishStatus status, UserContext userContext) {
        userProjectApplicationService.updateThirdAppPublishStatus(projectId, status);
    }
}
