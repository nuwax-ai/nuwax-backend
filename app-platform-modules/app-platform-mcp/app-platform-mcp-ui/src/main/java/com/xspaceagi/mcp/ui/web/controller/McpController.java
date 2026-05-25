package com.xspaceagi.mcp.ui.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.mcp.adapter.application.McpConfigApplicationService;
import com.xspaceagi.mcp.adapter.application.McpDeployTaskService;
import com.xspaceagi.mcp.adapter.dto.McpPageQueryDto;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.*;
import com.xspaceagi.mcp.sdk.enums.DeployStatusEnum;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.mcp.spec.utils.UrlExtractUtil;
import com.xspaceagi.mcp.ui.web.controller.dto.EnNameDto;
import com.xspaceagi.mcp.ui.web.controller.dto.McpCreateDto;
import com.xspaceagi.mcp.ui.web.controller.dto.McpTestDto;
import com.xspaceagi.mcp.ui.web.controller.dto.McpUpdateDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "MCP相关接口")
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private McpConfigApplicationService mcpConfigApplicationService;

    @Resource
    private McpDeployTaskService mcpDeployTaskService;

    @Resource
    private IMcpApiService iMcpApiService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @RequireResource(MCP_CREATE)
    @Operation(summary = "MCP服务创建")
    @PostMapping("/create")
    public ReqResult<McpDto> create(@RequestBody McpCreateDto mcpCreateDto) {
        spacePermissionService.checkSpaceUserPermission(mcpCreateDto.getSpaceId());
        Assert.notNull(mcpCreateDto.getInstallType(), "install type cannot be left blank.");
        Assert.notNull(mcpCreateDto.getMcpConfig(), "MCP config cannot be left blank.");
        checkServerConfig(mcpCreateDto.getInstallType(), mcpCreateDto.getMcpConfig());
        McpDto mcpDto = new McpDto();
        BeanUtils.copyProperties(mcpCreateDto, mcpDto);
        if (containsChinese(mcpCreateDto.getName())) {
            try {
                EnNameDto enNameDto = modelApplicationService.call(mcpCreateDto.getName(), new ParameterizedTypeReference<EnNameDto>() {
                });
                mcpDto.setServerName(enNameDto.getEnName());
            } catch (Exception e) {
            }
        } else {
            mcpDto.setServerName(mcpCreateDto.getName());
        }
        mcpDto.setCreatorId(RequestContext.get().getUserId());
        mcpConfigApplicationService.addMcp(mcpDto);
        if (mcpCreateDto.isWithDeploy()) {
            mcpDeployTaskService.addDeployTask(mcpDto);
        }
        McpDto mcp = mcpConfigApplicationService.getMcp(mcpDto.getId());
        clearMcpComponentConfig(mcp);
        return ReqResult.success(mcp);
    }

    @RequireResource(MCP_SAVE)
    @Operation(summary = "MCP服务更新")
    @PostMapping("/update")
    public ReqResult<McpDto> update(@RequestBody McpUpdateDto mcpUpdateDto) {
        Assert.notNull(mcpUpdateDto.getId(), "MCP ID cannot be left blank.");
        McpDto mcp = mcpConfigApplicationService.getMcp(mcpUpdateDto.getId());
        if (mcp == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpNotFound);
        }

        checkServerConfig(mcp.getInstallType(), mcpUpdateDto.getMcpConfig());

        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        if (!mcp.getCreatorId().equals(RequestContext.get().getUserId())) {
            spacePermissionService.checkSpaceAdminPermission(mcp.getSpaceId());
        }

        McpDto mcpDto = new McpDto();
        BeanUtils.copyProperties(mcpUpdateDto, mcpDto);
        //判断mcp.getName()是否含有中文
        if (mcpUpdateDto.getName() != null && !mcpUpdateDto.getName().equals(mcp.getName()) && containsChinese(mcpUpdateDto.getName())) {
            try {
                EnNameDto enNameDto = modelApplicationService.call(mcpUpdateDto.getName(), new ParameterizedTypeReference<EnNameDto>() {
                });
                mcpDto.setServerName(enNameDto.getEnName());
            } catch (Exception e) {
            }
        } else {
            mcpDto.setServerName(mcpUpdateDto.getName());
        }
        mcpConfigApplicationService.updateMcp(mcpDto);
        McpDto mcp1 = mcpConfigApplicationService.getMcp(mcpDto.getId());
        if (mcpUpdateDto.isWithDeploy()) {
            mcpDeployTaskService.addDeployTask(mcp1);
        }
        clearMcpComponentConfig(mcp1);
        return ReqResult.success(mcp1);
    }

    private static boolean containsChinese(String str) {
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]");
        Matcher matcher = pattern.matcher(str);
        return matcher.find();
    }

    private void checkServerConfig(InstallTypeEnum installType, McpConfigDto mcpConfig) {
        if (installType == InstallTypeEnum.COMPONENT || mcpConfig == null || mcpConfig.getServerConfig() == null) {
            return;
        }
        if (!JSON.isValid(mcpConfig.getServerConfig())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpServiceConfigJsonInvalid);
        }
        JSONObject serverConfig = JSONObject.parseObject(mcpConfig.getServerConfig());
        if (serverConfig == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpServiceConfigJsonInvalid);
        }
        if (installType == InstallTypeEnum.NPX) {
            if (!serverConfig.toJSONString().toLowerCase().contains("\"command\":\"npx\"")) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpNpxConfigInvalid);
            }
        }
        if (installType == InstallTypeEnum.UVX) {
            if (!serverConfig.toJSONString().toLowerCase().contains("\"command\":\"uvx\"")) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpUvxConfigInvalid);
            }
        }

        if (installType == InstallTypeEnum.STREAMABLE_HTTP) {
            List<String> list = UrlExtractUtil.extractUrls(mcpConfig.getServerConfig());
            if (list.size() == 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpStreamableHttpConfigInvalid);
            }
        }

        if (installType == InstallTypeEnum.SSE) {
            List<String> list = UrlExtractUtil.extractUrls(mcpConfig.getServerConfig());
            if (list.size() == 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpSseConfigInvalid);
            }
        }
    }

    @RequireResource(MCP_QUERY_DETAIL)
    @Operation(summary = "MCP详情查询")
    @GetMapping("/{id}")
    public ReqResult<McpDto> getOne(@PathVariable Long id) {
        McpDto mcp = mcpConfigApplicationService.getMcp(id);
        if (mcp == null) {
            return ReqResult.error("MCP does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(mcp.getSpaceId(), RequestContext.get().getUserId());
        mcp.setPermissions(generatePermissionList(spaceUserDto, mcp).stream().map(permission -> permission.name()).collect(Collectors.toList()));
        clearMcpComponentConfig(mcp);
        return ReqResult.success(mcp);
    }

    private void clearMcpComponentConfig(McpDto mcp) {
        if (mcp.getMcpConfig() != null && CollectionUtils.isNotEmpty(mcp.getMcpConfig().getComponents())) {
            List<McpComponentDto> components = mcp.getMcpConfig().getComponents();
            for (McpComponentDto component : components) {
                component.setTargetConfig(null);
            }
        }
    }

    @RequireResource(MCP_QUERY_LIST)
    @Operation(summary = "MCP管理列表")
    @GetMapping("/list/{spaceId}")
    public ReqResult<List<McpDto>> list(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        List<McpDto> mcpDtos = mcpConfigApplicationService.queryMcpListBySpaceId(spaceId);
        mcpDtos.forEach(mcpDto -> {
            List<SpaceObjectPermissionEnum> spaceObjectPermissionEnums = generatePermissionList(spaceUserDto, mcpDto);
            mcpDto.setPermissions(spaceObjectPermissionEnums.stream().map(SpaceObjectPermissionEnum::name).collect(Collectors.toList()));
        });
        return ReqResult.success(mcpDtos);
    }

    @RequireResource(MCP_QUERY_LIST)
    @Operation(summary = "MCP列表（官方服务）")
    @GetMapping("/official/list")
    public ReqResult<List<McpDto>> officialList() {
        List<McpDto> mcpDtos = mcpConfigApplicationService.queryMcpListBySpaceId(-1L);
        //移除未发布的
        mcpDtos.removeIf(mcpDto -> mcpDto.getDeployStatus() != DeployStatusEnum.Deployed);
        List<SpaceObjectPermissionEnum> permissionList = new ArrayList<>();
        mcpDtos.forEach(mcpDto -> mcpDto.setPermissions(permissionList.stream().map(SpaceObjectPermissionEnum::name).collect(Collectors.toList())));
        return ReqResult.success(mcpDtos);
    }

    @RequireResource(MCP_DELETE)
    @Operation(summary = "MCP删除")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@PathVariable Long id) {
        McpDto mcp = mcpConfigApplicationService.getMcp(id);
        if (mcp == null) {
            return ReqResult.error("MCP does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        if (!mcp.getCreatorId().equals(RequestContext.get().getUserId())) {
            spacePermissionService.checkSpaceAdminPermission(mcp.getSpaceId());
        }
        mcpConfigApplicationService.deleteMcp(id);
        return ReqResult.success();
    }

    @RequireResource(MCP_STOP)
    @Operation(summary = "MCP停用")
    @PostMapping("/stop/{id}")
    public ReqResult<Void> stop(@PathVariable Long id) {
        McpDto mcp = mcpConfigApplicationService.getMcp(id);
        if (mcp == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        if (!mcp.getCreatorId().equals(RequestContext.get().getUserId())) {
            spacePermissionService.checkSpaceAdminPermission(mcp.getSpaceId());
        }
        McpDto update = new McpDto();
        update.setId(mcp.getId());
        update.setDeployStatus(DeployStatusEnum.Stopped);
        mcpConfigApplicationService.updateMcp(update);
        return ReqResult.success();
    }

    @RequireResource(MCP_EXPORT)
    @Operation(summary = "MCP服务导出")
    @PostMapping("/server/config/export/{id}")
    public ReqResult<String> export(@PathVariable Long id) {
        McpDto mcp = mcpConfigApplicationService.getDeployedMcp(id);
        if (mcp == null) {
            return ReqResult.error("MCP does not exist or deployment not completed");
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        boolean allowMcpExport = tenantConfigDto.getAllowMcpExport() == null || tenantConfigDto.getAllowMcpExport().equals(YesOrNoEnum.Y.getKey());
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (!allowMcpExport && userDto.getRole() != User.Role.Admin) {
            return ReqResult.error("MCP export is currently not allowed");
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        return ReqResult.success(mcpConfigApplicationService.getExportMcpServerConfig(RequestContext.get().getUserId(), id, null));
    }

    @RequireResource(MCP_EXPORT)
    @Operation(summary = "MCP服务重新生成配置")
    @PostMapping("/server/config/refresh/{id}")
    public ReqResult<String> exportRefresh(@PathVariable Long id) {
        McpDto mcp = mcpConfigApplicationService.getDeployedMcp(id);
        if (mcp == null) {
            return ReqResult.error("MCP does not exist or deployment not completed");
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        return ReqResult.success(mcpConfigApplicationService.refreshExportMcpServerConfig(RequestContext.get().getUserId(), id));
    }

    @RequireResource(MCP_QUERY_LIST)
    @Operation(summary = "MCP已发布服务列表（弹框使用）")
    @GetMapping("/deployed/list/{spaceId}")
    public ReqResult<List<McpDto>> deployedList(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        McpPageQueryDto mcpPageQueryDto = new McpPageQueryDto();
        mcpPageQueryDto.setSpaceId(spaceId);
        mcpPageQueryDto.setPage(1);
        mcpPageQueryDto.setPageSize(100);
        List<McpDto> mcpDtos = mcpConfigApplicationService.queryDeployedMcpList(mcpPageQueryDto).getRecords();
        mcpDtos.forEach(mcpDto -> {
            List<SpaceObjectPermissionEnum> spaceObjectPermissionEnums = generatePermissionList(spaceUserDto, mcpDto);
            mcpDto.setPermissions(spaceObjectPermissionEnums.stream().map(SpaceObjectPermissionEnum::name).collect(Collectors.toList()));
        });
        return ReqResult.success(mcpDtos);
    }

    @RequireResource(MCP_QUERY_LIST)
    @Operation(summary = "MCP已发布服务列表（弹框使用-新）")
    @PostMapping("/deployed/list")
    public ReqResult<IPage<McpDto>> deployedList(@RequestBody @Valid McpPageQueryDto mcpPageQueryDto) {
        if (mcpPageQueryDto.getSpaceId() != null) {
            spacePermissionService.checkSpaceUserPermission(mcpPageQueryDto.getSpaceId());
        }
        IPage<McpDto> mcpDtoIPage = mcpConfigApplicationService.queryDeployedMcpList(mcpPageQueryDto);
        return ReqResult.success(mcpDtoIPage);
    }

    @RequireResource(MCP_QUERY_DETAIL)
    @Operation(summary = "MCP试运行")
    @PostMapping("/test")
    public ReqResult<McpExecuteOutput> test(@RequestBody McpTestDto mcpTestDto) {
        Assert.notNull(mcpTestDto, "mcpExecuteRequestDto must be non-null");
        Assert.notNull(mcpTestDto.getId(), "id must be non-null");
        McpDto mcp = mcpConfigApplicationService.getDeployedMcp(mcpTestDto.getId());
        if (mcp == null) {
            return ReqResult.error("MCP not deployed or disabled");
        }
        spacePermissionService.checkSpaceUserPermission(mcp.getSpaceId());
        McpExecuteRequest mcpExecuteRequest = McpExecuteRequest.builder()
                .sessionId(UUID.randomUUID().toString().replace("-", ""))
                .user(RequestContext.get().getUser())
                .mcpDto(mcp)
                .executeType(mcpTestDto.getExecuteType())
                .requestId(mcpTestDto.getRequestId())
                .params(mcpTestDto.getParams())
                .keepAlive(false)
                .name(mcpTestDto.getName()).build();
        McpExecuteOutput mcpExecuteOutput;
        try {
            mcpExecuteOutput = iMcpApiService.execute(mcpExecuteRequest).blockLast();
        } catch (Exception e) {
            return ReqResult.error(e.getMessage());
        }
        return ReqResult.success(mcpExecuteOutput);
    }


    public static List<SpaceObjectPermissionEnum> generatePermissionList(SpaceUserDto spaceUserDto, McpDto mcpDto) {
        List<SpaceObjectPermissionEnum> permissionList = new ArrayList<>();
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        boolean allowMcpExport = tenantConfigDto.getAllowMcpExport() == null || tenantConfigDto.getAllowMcpExport().equals(YesOrNoEnum.Y.getKey());
        if (mcpDto.isPlatformMcp()) {
            allowMcpExport = false;//平台提供的MCP不允许导出
        }
        UserDto user = (UserDto) RequestContext.get().getUser();
        if ((allowMcpExport || (!mcpDto.isPlatformMcp() && user != null && user.getRole() == User.Role.Admin)) && (mcpDto.getDeployStatus() == DeployStatusEnum.Deployed || mcpDto.getDeployStatus() == DeployStatusEnum.Deploying)) {
            permissionList.add(SpaceObjectPermissionEnum.Export);
        }
        if (user != null && user.getRole() == User.Role.Admin) {
            permissionList.add(SpaceObjectPermissionEnum.EditOrDeploy);
            permissionList.add(SpaceObjectPermissionEnum.Delete);
            if (mcpDto.getDeployStatus() == DeployStatusEnum.Deployed) {
                permissionList.add(SpaceObjectPermissionEnum.Stop);
            }
            return permissionList;
        }
        if (spaceUserDto == null) {
            permissionList.clear();
            return permissionList;
        }
        if (spaceUserDto.getUserId().equals(mcpDto.getCreatorId()) || spaceUserDto.getRole() == SpaceUser.Role.Admin || spaceUserDto.getRole() == SpaceUser.Role.Owner) {
            permissionList.add(SpaceObjectPermissionEnum.EditOrDeploy);
            permissionList.add(SpaceObjectPermissionEnum.Delete);
            if (mcpDto.getDeployStatus() == DeployStatusEnum.Deployed) {
                permissionList.add(SpaceObjectPermissionEnum.Stop);
            }
        }
        return permissionList;
    }

    public enum SpaceObjectPermissionEnum {
        Delete,
        EditOrDeploy,
        Export,
        Stop,
    }
}
