package com.xspaceagi.custompage.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.service.ICustomPageBuildDomainService;
import com.xspaceagi.custompage.domain.service.ICustomPageConfigDomainService;
import com.xspaceagi.custompage.infra.dao.entity.CustomPageConfig;
import com.xspaceagi.custompage.infra.dao.service.ICustomPageConfigService;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.custompage.sdk.dto.CustomPageQueryReq;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.DateUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户页面RPC服务实现
 */
@Slf4j
@Service
public class CustomPageRpcServiceImpl implements ICustomPageRpcService {

    @Resource
    private UserApplicationService userApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private PluginApplicationService pluginApplicationService;
    @Resource
    private WorkflowApplicationService workflowApplicationService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathService;
    @Resource
    private ICustomPageBuildDomainService customPageBuildDomainService;
    @Resource
    private ICustomPageConfigDomainService customPageConfigDomainService;

    @Resource
    private ICustomPageConfigService customPageConfigService;
    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;

    @Override
    public List<CustomPageDto> list(CustomPageQueryReq req) {
        log.info("[RPC] queryfrontend pageprojectlist, request={}", req);
        if (req == null) {
            throw new IllegalArgumentException("Parameters are empty");
        }
        if (req.getSpaceId() == null) {
            throw new IllegalArgumentException("spaceId is required");
        }
        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(req.getSpaceId());

        CustomPageConfigModel configModel = new CustomPageConfigModel();
        configModel.setSpaceId(req.getSpaceId());
        configModel.setCreatorId(req.getUserId());
        configModel.setBuildRunning(req.getBuildRunning() == null ? null : req.getBuildRunning() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());
        configModel.setPublishType(req.getPublishType());

        List<CustomPageConfigModel> configModelList = customPageConfigDomainService.list(configModel);
        if (configModelList == null || configModelList.isEmpty()) {
            return new ArrayList<>();
        }
        List<CustomPageConfigModel> sortedConfigList = configModelList;

        List<Long> projectIdList = configModelList.stream().map(CustomPageConfigModel::getId).collect(Collectors.toList());
        List<CustomPageBuildModel> buildModelList = customPageBuildDomainService.listByProjectIds(projectIdList);

        if (buildModelList != null && !buildModelList.isEmpty()) {
            // 获取versionInfo中最大版本的time，转换成projectId->time的映射
            Map<Long, Date> projectIdToMaxVersionTimeMap = new HashMap<>();

            for (CustomPageBuildModel build : buildModelList) {
                if (build.getVersionInfo() != null && !build.getVersionInfo().isEmpty()) {
                    Optional<String> maxVersionTime = build.getVersionInfo().stream().filter(v -> v.getVersion() != null && v.getTime() != null).max(Comparator.comparing(com.xspaceagi.custompage.sdk.dto.VersionInfoDto::getVersion)).map(com.xspaceagi.custompage.sdk.dto.VersionInfoDto::getTime);
                    if (maxVersionTime.isPresent()) {
                        try {
                            projectIdToMaxVersionTimeMap.put(build.getProjectId(), DateUtil.parse(maxVersionTime.get(), "yyyy-MM-dd HH:mm:ss"));
                        } catch (ParseException e) {
                            log.warn("[RPC] parse version time failed, project Id={}, time={}, error={}", build.getProjectId(), maxVersionTime.get(), e.getMessage());
                            projectIdToMaxVersionTimeMap.put(build.getProjectId(), build.getModified());
                        }
                    }
                } else {
                    projectIdToMaxVersionTimeMap.put(build.getProjectId(), build.getModified());
                }
            }

            sortedConfigList = configModelList.stream().sorted(Comparator.<CustomPageConfigModel, Date>comparing(config -> {
                Date maxVersionTime = projectIdToMaxVersionTimeMap.get(config.getId());
                return maxVersionTime != null ? maxVersionTime : (config.getModified() != null ? config.getModified() : config.getCreated());
            }, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        }

        List<CustomPageDto> dtoList = sortedConfigList.stream().map((CustomPageConfigModel model) -> convertToDto(model, false)).collect(Collectors.toList());

        // 补充用户信息
        completeCreator(dtoList);

        log.info("[RPC] queryfrontend pageprojectlistresponse, count={}", dtoList.size());
        return dtoList;
    }

    @Override
    public SuperPage<CustomPageDto> pageQuery(PageQueryVo<CustomPageQueryReq> pageQueryVo) {
        log.info("[RPC] pagedqueryfrontend pageproject, request={}", pageQueryVo);

        CustomPageQueryReq req = pageQueryVo.getQueryFilter();
        if (req == null) {
            throw new IllegalArgumentException("Parameters are empty");
        }

        CustomPageConfigModel configModel = new CustomPageConfigModel();
        configModel.setSpaceId(req.getSpaceId());
        configModel.setCreatorId(req.getUserId());
        configModel.setBuildRunning(req.getBuildRunning() == null ? null : req.getBuildRunning() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());

        Long current = pageQueryVo.getCurrent();
        Long pageSize = pageQueryVo.getPageSize();

        SuperPage<CustomPageConfigModel> page = customPageConfigDomainService.pageQuery(configModel, current, pageSize);

        SuperPage<CustomPageDto> dtoPage = convertToDtoPage(page);

        // 补充用户信息
        completeCreator(dtoPage.getRecords());

        log.info("[RPC] pagedqueryfrontend pageprojectresponse, count={}", dtoPage.getTotal());
        return dtoPage;
    }

    @Override
    public CustomPageDto queryDetail(Long projectId) {
        log.info("[RPC] Query custom page project detail, project Id={}", projectId);
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        CustomPageConfigModel configModel = customPageConfigDomainService.getById(projectId);
        if (configModel == null) {
            return null;
        }

        CustomPageDto dto = convertToDto(configModel, true);

        // 补充用户信息
        completeCreator(Collections.singletonList(dto));

        if (YesOrNoEnum.Y.getKey().equals(configModel.getBuildRunning())) {
            try {
                String prodProxyPath = customPageProxyPathService.getProdProxyPath(configModel);
                dto.setPageUrl(prodProxyPath);
            } catch (Exception e) {
                log.info("[RPC] project Id={}, prod Proxy Path not found", projectId);
            }
        }
        return dto;
    }

    @Override
    public CustomPageDto queryDetailWithVersion(Long projectId) {
        log.info("[RPC] Query custom page project detail, project Id={}", projectId);
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        CustomPageConfigModel configModel = customPageConfigDomainService.getById(projectId);
        if (configModel == null) {
            return null;
        }
        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        CustomPageDto dto = convertToDto(configModel, true);

        // 补充用户信息
        completeCreator(Collections.singletonList(dto));

        CustomPageBuildModel buildModel = customPageBuildDomainService.getByProjectId(projectId);
        if (buildModel != null) {
            dto.setBuildRunning(Objects.equals(buildModel.getBuildRunning(), YesOrNoEnum.Y.getKey()));
            dto.setBuildTime(buildModel.getBuildTime());
            dto.setBuildVersion(buildModel.getBuildVersion());
            dto.setCodeVersion(buildModel.getCodeVersion());
            dto.setVersionInfo(buildModel.getVersionInfo());
            if (buildModel.getLastChatModelId() != null) {
                dto.setLastChatModelId(buildModel.getLastChatModelId());
            } else {
                TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
                if (tenantConfig == null || tenantConfig.getDefaultCodingModelId() == null || tenantConfig.getDefaultCodingModelId() == 0) {
                    log.info("[RPC] project Id={},query frontend page project detail, no default chat model configured", projectId);
                } else {
                    dto.setLastChatModelId(tenantConfig.getDefaultCodingModelId());
                }

            }
            if (buildModel.getLastMultiModelId() != null) {
                dto.setLastMultiModelId(buildModel.getLastMultiModelId());
            } else {
                // 从 RequestContext 获取租户配置
                TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
                if (tenantConfig == null || tenantConfig.getDefaultVisualModelId() == null || tenantConfig.getDefaultVisualModelId() == 0) {
                    log.info("[RPC] project Id={},query frontend page project detail, no default multimodal model configured", projectId);
                } else {
                    dto.setLastMultiModelId(tenantConfig.getDefaultVisualModelId());
                }
            }
        }

        if (YesOrNoEnum.Y.getKey().equals(configModel.getBuildRunning())) {
            try {
                String prodProxyPath = customPageProxyPathService.getProdProxyPath(configModel);
                dto.setPageUrl(prodProxyPath);
            } catch (Exception e) {
                log.info("[RPC] project Id={}, prod Proxy Path not found", projectId);
            }
        }
        return dto;
    }

    @Override
    public CustomPageDto queryDetailByAgentId(Long agentId) {
        log.info("[RPC] query project detail by agent Id, agent Id={}", agentId);
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        CustomPageConfigModel configModel = customPageConfigDomainService.getByAgentId(agentId);
        if (configModel == null) {
            return null;
        }

        CustomPageDto dto = convertToDto(configModel, true);

        // 补充用户信息
        completeCreator(Collections.singletonList(dto));

        if (YesOrNoEnum.Y.getKey().equals(configModel.getBuildRunning())) {
            try {
                String prodProxyPath = customPageProxyPathService.getProdProxyPath(configModel);
                dto.setPageUrl(prodProxyPath);
            } catch (Exception e) {
                log.info("[RPC] agent Id={}, not foundprod Proxy Path", agentId);
            }
        }
        return dto;
    }

    private void completeCreator(List<CustomPageDto> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return;
        }
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(dtoList.stream().map(CustomPageDto::getCreatorId).collect(Collectors.toList()));

        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto, (v1, v2) -> v1));
        // TenantConfigDto tenantConfigDto = (TenantConfigDto)
        // RequestContext.get().getTenantConfig();

        dtoList.forEach(dto -> {
            UserDto userDto = userMap.get(dto.getCreatorId());
            if (userDto == null) {
                userDto = new UserDto();
                userDto.setId(-1L);
                userDto.setUserName("");
            }

            dto.setCreatorId(userDto.getId());
            dto.setCreatorName(userDto.getUserName());
            dto.setCreatorNickName(userDto.getNickName());
            dto.setCreatorAvatar(userDto.getAvatar());
        });
    }

    /**
     * 转换Model分页结果到DTO分页结果
     */
    private SuperPage<CustomPageDto> convertToDtoPage(SuperPage<CustomPageConfigModel> modelPage) {
        SuperPage<CustomPageDto> dtoPage = new SuperPage<>();
        dtoPage.setCurrent(modelPage.getCurrent());
        dtoPage.setSize(modelPage.getSize());
        dtoPage.setTotal(modelPage.getTotal());

        if (modelPage.getRecords() != null) {
            dtoPage.setRecords(modelPage.getRecords().stream().map(model -> convertToDto(model, false)).collect(Collectors.toList()));
        }
        return dtoPage;
    }

    /**
     * 转换Model到DTO
     */
    private CustomPageDto convertToDto(CustomPageConfigModel model, boolean completeDataSources) {
        CustomPageDto dto = convertToDtoBasic(model);
        if (dto == null) {
            return null;
        }

        if (completeDataSources && CollectionUtils.isNotEmpty(dto.getDataSources())) {
            dto.getDataSources().forEach(dataSource -> {
                String type = dataSource.getType();
                Long dataSourceId = dataSource.getId();

                if ("plugin".equalsIgnoreCase(type)) {
                    PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(dataSourceId, null);
                    if (pluginDto != null) {
                        dataSource.setName(pluginDto.getName());
                        dataSource.setIcon(pluginDto.getIcon());
                    } else {
                        log.error("[bind Data Source] project Id={},type={},data Source Id={},pluginnot foundor not published, plugin Id={}", model.getId(), type, dataSourceId, dataSourceId);
                    }
                } else if ("workflow".equalsIgnoreCase(type)) {
                    WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(dataSourceId, null);
                    if (workflowConfigDto != null) {
                        dataSource.setName(workflowConfigDto.getName());
                        dataSource.setIcon(workflowConfigDto.getIcon());
                    } else {
                        log.error("[bind Data Source] project Id={},type={},data Source Id={},workflownot foundor not published, workflow Id={}", model.getId(), type, dataSourceId, dataSourceId);
                    }
                }

                if (dataSource.getIcon() == null || dataSource.getIcon().isEmpty()) {
                    dataSource.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(dataSource.getIcon(), dataSource.getName(), dataSource.getType()));
                }
            });
        }
        return dto;
    }

    private CustomPageDto convertToDtoBasic(CustomPageConfigModel model) {
        if (model == null) {
            return null;
        }
        CustomPageDto dto = new CustomPageDto();
        dto.setProjectId(model.getId());
        dto.setName(model.getName());
        dto.setDescription(model.getDescription());
        dto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(model.getIcon(), model.getName(), "page"));
        dto.setCoverImg(model.getCoverImg());
        dto.setCoverImgSourceType(model.getCoverImgSourceType());
        dto.setBasePath(model.getBasePath());
        dto.setBuildRunning(YesOrNoEnum.Y.getKey().equals(model.getBuildRunning()));
        dto.setPublishType(model.getPublishType());
        dto.setNeedLogin(YesOrNoEnum.Y.getKey().equals(model.getNeedLogin()));
        dto.setDevAgentId(model.getDevAgentId());
        dto.setProjectType(model.getProjectType());
        dto.setProxyConfigs(model.getProxyConfigs());
        dto.setPageArgConfigs(model.getPageArgConfigs());
        dto.setDataSources(model.getDataSources());
        dto.setExt(model.getExt());
        dto.setTenantId(model.getTenantId());
        dto.setSpaceId(model.getSpaceId());
        dto.setCreated(model.getCreated());
        dto.setCreatorId(model.getCreatorId());
        dto.setCreatorName(model.getCreatorName());
        return dto;
    }

    @Override
    public List<CustomPageDto> listByAgentIds(List<Long> agentIds) {
        if (CollectionUtils.isEmpty(agentIds)) {
            return new ArrayList<>();
        }
        List<CustomPageConfigModel> modelList = customPageConfigDomainService.listByDevAgentIds(agentIds);
        if (CollectionUtils.isEmpty(modelList)) {
            return new ArrayList<>();
        }
        List<CustomPageDto> dtoList = modelList.stream().map((CustomPageConfigModel model) -> convertToDto(model, false)).collect(Collectors.toList());
        // 补充用户信息
        completeCreator(dtoList);
        return dtoList;
    }

    @Override
    public Long countTotalPages() {
        return customPageConfigDomainService.countTotalPages();
    }

    @Override
    public IPage<CustomPageDto> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds,
                                                   Long spaceId, List<Long> devAgentIds) {

        Page<CustomPageConfig> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<CustomPageConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(spaceId != null && spaceId > 0, CustomPageConfig::getSpaceId, spaceId);
        queryWrapper.in(devAgentIds != null && !devAgentIds.isEmpty(), CustomPageConfig::getDevAgentId, devAgentIds);
        queryWrapper.like(StringUtils.isNotBlank(name), CustomPageConfig::getName, name);
        queryWrapper.in(creatorIds != null && !creatorIds.isEmpty(), CustomPageConfig::getCreatorId, creatorIds);
        queryWrapper.orderByDesc(CustomPageConfig::getCreated);
        Page<CustomPageConfig> customPageConfigPage = customPageConfigService.page(page, queryWrapper);

        return customPageConfigPage.convert(model -> {
            CustomPageDto dto = new CustomPageDto();
            dto.setProjectId(model.getId());
            dto.setName(model.getName());
            dto.setDescription(model.getDescription());
            dto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(model.getIcon(), model.getName(), "page"));
            dto.setCoverImg(model.getCoverImg());
            dto.setCoverImgSourceType(model.getCoverImgSourceType());
            dto.setBasePath(model.getBasePath());
            dto.setBuildRunning(YesOrNoEnum.Y.getKey().equals(model.getBuildRunning()));
            dto.setPublishType(model.getPublishType());
            dto.setNeedLogin(YesOrNoEnum.Y.getKey().equals(model.getNeedLogin()));
            dto.setDevAgentId(model.getDevAgentId());
            dto.setProjectType(model.getProjectType());
            dto.setCreatorId(model.getCreatorId());
            dto.setCreatorName(model.getCreatorName());
            dto.setCreated(model.getCreated());
            dto.setSpaceId(model.getSpaceId());
            return dto;
        });

    }

    @Override
    public void deleteForManage(Long id) {
        var model = customPageConfigDomainService.getById(id);
        if (model != null) {
            customPageConfigApplicationService.deleteProject(id, com.xspaceagi.system.spec.common.UserContext.builder()
                    .userId(0L)
                    .userName("admin")
                    .tenantId(model.getTenantId())
                    .build());
        }
    }

    @Override
    public List<CustomPageDto> listByIds(List<Long> pageIds, List<Long> agentIds) {
        List<CustomPageConfigModel> modelList = null;
        if (CollectionUtils.isNotEmpty(pageIds)) {
            modelList = customPageConfigDomainService.listByIds(pageIds);
        } else if (CollectionUtils.isNotEmpty(agentIds)) {
            modelList = customPageConfigDomainService.listByDevAgentIds(agentIds);
        }
        if (CollectionUtils.isEmpty(modelList)) {
            return List.of();
        }
        return modelList.stream().map(this::convertToDtoBasic).collect(Collectors.toList());
    }
}
