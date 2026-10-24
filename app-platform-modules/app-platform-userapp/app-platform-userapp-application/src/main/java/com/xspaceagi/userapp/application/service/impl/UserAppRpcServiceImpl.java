package com.xspaceagi.userapp.application.service.impl;

import cn.hutool.core.util.StrUtil;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.application.service.IUserAppApplicationService;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserAppRpcServiceImpl implements IUserAppRpcService {

    @Resource
    private IUserAppApplicationService userAppApplicationService;
    @Resource
    private IUserProjectApplicationService userProjectApplicationService;

    @Override
    public UserAppDto ensureRepoResources(Long id) {
        return withNameDefined(convertToDto(userAppApplicationService.ensureRepoResources(id)));
    }

    @Override
    public UserAppDto queryById(Long id) {
        return withNameDefined(convertToDto(userAppApplicationService.getById(id)));
    }

    @Override
    public List<UserAppDto> list(UserAppQueryReq req) {
        return userAppApplicationService.list(toQueryModel(req)).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public SuperPage<UserAppDto> pageQuery(PageQueryVo<UserAppQueryReq> pageQueryVo) {
        PageQueryVo<UserAppQueryReq> vo = pageQueryVo == null ? new PageQueryVo<>() : pageQueryVo;
        SuperPage<UserAppModel> page = userAppApplicationService.pageQuery(
                toQueryModel(vo.getQueryFilter()), vo.getCurrent(), vo.getPageSize());
        List<UserAppDto> records = page.getRecords() == null ? List.of()
                : page.getRecords().stream().map(this::convertToDto).collect(Collectors.toList());
        return new SuperPage<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Override
    public UserAppDto create(UserAppCreateDto createDto) {
        UserAppModel model = new UserAppModel();
        model.setCreatorId(createDto.getCreatorId());
        model.setSpaceId(createDto.getSpaceId());
        model.setName(createDto.getName());
        model.setDescription(createDto.getDescription());
        model.setIcon(createDto.getIcon());
        model.setSandboxId(createDto.getSandboxId());
        model.setDevAgentId(createDto.getDevAgentId());
        return withNameDefined(convertToDto(userAppApplicationService.create(model, createDto.getWorkspacePath())));
    }

    /** 单条回填 nameDefined（来源统一登记表 user_project，登记行缺失保持 null）；列表/分页不返回，进详情时查 */
    private UserAppDto withNameDefined(UserAppDto dto) {
        if (dto == null) {
            return null;
        }
        UserProjectModel project = userProjectApplicationService.getByRef(ProjectTypeEnum.UserApp, dto.getId());
        if (project != null) {
            dto.setNameDefined(Boolean.TRUE.equals(project.getNameDefined()));
        }
        return dto;
    }

    @Override
    public void updateBasicInfo(Long id, String name, String description, String icon, String coverImg, UserContext userContext) {
        UserAppModel model = new UserAppModel();
        model.setId(id);
        model.setName(name);
        model.setDescription(description);
        model.setIcon(icon);
        model.setCoverImg(coverImg);
        userAppApplicationService.update(model, userContext);
    }

    @Override
    public void delete(Long id, UserContext userContext) {
        userAppApplicationService.delete(id, userContext);
    }

    @Override
    public String saveDbCredential(Long id, boolean devEnv, String username, String password, UserContext userContext) {
        return userAppApplicationService.saveDbCredential(id, devEnv, username, password, userContext);
    }

    @Override
    public String getDbPassword(Long id, boolean devEnv) {
        return userAppApplicationService.getDbPassword(id, devEnv);
    }

    @Override
    public void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext) {
        userAppApplicationService.updatePublishStatus(id, status, userContext);
    }

    @Override
    public BuildVersionDto checkPublishPrecondition(Long appId) {
        return userAppApplicationService.checkPublishPrecondition(appId);
    }

    @Override
    public List<UserAppDto> listPreDeleteDue() {
        return userAppApplicationService.listPreDeleteDue().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }


    @Override
    public void purge(Long id) {
        userAppApplicationService.purge(id);
    }

    private UserAppModel toQueryModel(UserAppQueryReq req) {
        UserAppModel query = new UserAppModel();
        if (req != null) {
            query.setSpaceId(req.getSpaceId());
            query.setCreatorId(req.getCreatorId());
            query.setPublishStatus(req.getPublishStatus());
            query.setName(req.getName());
        }
        return query;
    }

    private UserAppDto convertToDto(UserAppModel model) {
        if (model == null) {
            return null;
        }
        UserAppDto dto = new UserAppDto();
        dto.setId(model.getId());
        dto.setTenantId(model.getTenantId());
        dto.setSpaceId(model.getSpaceId());
        dto.setCreatorId(model.getCreatorId());
        dto.setName(model.getName());
        dto.setDescription(model.getDescription());
        dto.setIcon(model.getIcon());
        dto.setCoverImg(model.getCoverImg());
        dto.setSandboxId(model.getSandboxId());
        dto.setDeployType(model.getDeployType() == null ? null : model.getDeployType().getValue());
        dto.setDeployServerId(model.getDeployServerId());
        dto.setDevAgentId(model.getDevAgentId());
        dto.setRepoSlugId(model.getRepoSlugId());
        dto.setProdDeployed(Boolean.TRUE.equals(model.getProdDeployed()));
        dto.setProdRunning(Boolean.TRUE.equals(model.getProdRunning()));
        dto.setProdReleaseId(model.getProdReleaseId());
        dto.setPlanSlugId(model.getPlanSlugId());
        dto.setSandboxType(model.getSandboxType());
        dto.setPublishStatus(model.getPublishStatus());
        dto.setBuildVersions(model.getBuildVersions());
        dto.setDevDbUsername(model.getDevDbUsername());
        dto.setDevDbPasswordSet(StrUtil.isNotBlank(model.getDevDbPassword()));
        dto.setProdDbUsername(model.getProdDbUsername());
        dto.setProdDbPasswordSet(StrUtil.isNotBlank(model.getProdDbPassword()));
        dto.setModified(model.getModified());
        dto.setCreated(model.getCreated());
        return dto;
    }
}
