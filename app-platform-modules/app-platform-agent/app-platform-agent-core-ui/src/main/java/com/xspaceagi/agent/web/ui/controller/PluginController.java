package com.xspaceagi.agent.web.ui.controller;

import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.ConfigHistoryApplicationService;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.CodePluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.HttpPluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.PluginPublishApplyDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "插件相关接口")
@RestController
@RequestMapping("/api/plugin")
@Slf4j
public class PluginController {

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ConfigHistoryApplicationService configHistoryApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @RequireResource(COMPONENT_LIB_CREATE)
    @Operation(summary = "新增插件接口")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody @Valid PluginAddDto pluginAddDto) {
        spacePermissionService.checkSpaceUserPermission(pluginAddDto.getSpaceId());
        pluginAddDto.setCreatorId(RequestContext.get().getUserId());
        Long pluginId = pluginApplicationService.add(pluginAddDto);
        return ReqResult.success(pluginId);
    }

    @RequireResource(COMPONENT_LIB_COPY_TO_SPACE)
    @Operation(summary = "复制到空间接口")
    @RequestMapping(path = "/copy/{pluginId}/{targetSpaceId}", method = RequestMethod.POST)
    public ReqResult<Long> copyToSpace(@PathVariable Long pluginId, @PathVariable Long targetSpaceId) {
        PluginDto pluginDto = pluginApplicationService.queryById(pluginId);
        if (pluginDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginIdInvalid);
        }
        // 检查权限
        if (targetSpaceId.equals(pluginDto.getSpaceId())) {
            // 复制到本空间，只需检查普通用户权限
            spacePermissionService.checkSpaceUserPermission(pluginDto.getSpaceId());
        } else {
            // 复制到本空间，只有管理员可复制
            spacePermissionService.checkSpaceAdminPermission(pluginDto.getSpaceId());
            // 复制到其他空间，判断目标空间权限
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }
        Long id = pluginApplicationService.copyPlugin(RequestContext.get().getUserId(), pluginDto, targetSpaceId);
        return ReqResult.success(id);
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "查询插件信息")
    @RequestMapping(path = "/{pluginId}", method = RequestMethod.GET)
    public ReqResult<PluginDto> getPluginConfig(@PathVariable Long pluginId) {
        PluginDto pluginDto = pluginApplicationService.queryById(pluginId);
        if (pluginDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(pluginDto.getSpaceId());
        //为了前端好处理。重新将参数的key处理
        PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
        if (pluginConfigDto != null) {
            resetArgsKey(pluginConfigDto.getInputArgs());
            resetArgsKey(pluginConfigDto.getOutputArgs());
        }
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(pluginDto.getSpaceId(), RequestContext.get().getUserId());
        pluginDto.setPermissions(SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, pluginDto.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList()));
        return ReqResult.success(pluginDto);
    }

    private void resetArgsKey(List<Arg> args) {
        if (args != null) {
            args.forEach(arg -> {
                arg.setKey(UUID.randomUUID().toString().replace("-", ""));
                resetArgsKey(arg.getSubArgs());
            });
        }
    }

    @Operation(summary = "查询插件历史配置信息接口")
    @RequestMapping(path = "/config/history/list/{pluginId}", method = RequestMethod.GET)
    public ReqResult<List<ConfigHistoryDto>> historyList(@PathVariable Long pluginId) {
        checkPluginPermission(pluginId);
        List<ConfigHistoryDto> historyList = configHistoryApplicationService.queryConfigHistoryList(Published.TargetType.Plugin, pluginId);
        return ReqResult.success(historyList);
    }

    @RequireResource(COMPONENT_LIB_DELETE)
    @Operation(summary = "删除插件接口")
    @RequestMapping(path = "/delete/{pluginId}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long pluginId) {
        checkPluginPermission(pluginId);
        pluginApplicationService.delete(pluginId);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新HTTP插件配置接口")
    @RequestMapping(path = "/http/update", method = RequestMethod.POST)
    public ReqResult<Void> updateHttpPlugin(@RequestBody PluginUpdateDto<HttpPluginConfigDto> pluginUpdateDto) {
        PluginDto pluginDto = checkPluginPermission(pluginUpdateDto.getId());
        if (pluginUpdateDto.getConfig() != null) {
            pluginDto.setConfig(pluginUpdateDto.getConfig());
            //validatePluginConfig(pluginDto);
        }
        pluginApplicationService.update(pluginUpdateDto);
        return ReqResult.success();
    }

    @Operation(summary = "自动解析插件出参")
    @RequestMapping(path = "/analysis/output", method = RequestMethod.POST)
    public ReqResult<List<Arg>> analysisHttpPluginOutput(@RequestBody AnalysisHttpPluginOutputDto analysisHttpPluginOutputDto) {
        PluginDto pluginDto = checkPluginPermission(analysisHttpPluginOutputDto.getPluginId());
        if (pluginDto.getConfig() != null) {
            validatePluginConfig(pluginDto);
        }
        List<Arg> args = pluginApplicationService.analysisPluginOutput(analysisHttpPluginOutputDto);
        return ReqResult.success(args);
    }

    @RequireResource(COMPONENT_LIB_MODIFY)
    @Operation(summary = "更新代码插件配置接口")
    @RequestMapping(path = "/code/update", method = RequestMethod.POST)
    public ReqResult<Void> updateCodePlugin(@RequestBody PluginUpdateDto<CodePluginConfigDto> pluginUpdateDto) {
        PluginDto pluginDto = checkPluginPermission(pluginUpdateDto.getId());
        if (pluginUpdateDto.getConfig() != null) {
            pluginDto.setConfig(pluginUpdateDto.getConfig());
            //validatePluginConfig(pluginDto);
        }
        pluginApplicationService.update(pluginUpdateDto);
        return ReqResult.success();
    }

    private void validatePluginConfig(PluginDto pluginDto) {
        List<String> messages = pluginApplicationService.validatePluginConfig(pluginDto);
        if (!messages.isEmpty()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), Joiner.on(",").join(messages));
        }
    }

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @Operation(summary = "插件试运行接口")
    @RequestMapping(path = "/test", method = RequestMethod.POST)
    public ReqResult<PluginExecuteResultDto> testExecute(@RequestBody PluginExecuteRequestDto pluginExecuteRequestDto) {
        PluginDto pluginDto = checkPluginPermission(pluginExecuteRequestDto.getPluginId());
        List<String> messages = pluginApplicationService.validatePluginConfig(pluginDto);
        if (!messages.isEmpty()) {
            return ReqResult.error(Joiner.on(",").join(messages));
        }
        pluginExecuteRequestDto.setTest(true);
        PluginExecuteResultDto pluginExecuteResultDto = pluginApplicationService.execute(pluginExecuteRequestDto, pluginDto);
        return ReqResult.success(pluginExecuteResultDto);
    }

    @RequireResource(COMPONENT_LIB_PUBLISH)
    @Operation(summary = "插件发布")
    @RequestMapping(path = "/publish", method = RequestMethod.POST)
    public ReqResult<String> publishApply(@RequestBody PluginPublishApplyDto pluginPublishApplyDto) {
        checkPluginPermission(pluginPublishApplyDto.getPluginId());
        PluginDto pluginDto = pluginApplicationService.queryById(pluginPublishApplyDto.getPluginId());
        if (pluginDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginIdError);
        }
        List<String> messages = pluginApplicationService.validatePluginConfig(pluginDto);
        if (!messages.isEmpty()) {
            return ReqResult.error(Joiner.on(",").join(messages));
        }
        PublishApplyDto publishApplyDto = new PublishApplyDto();
        publishApplyDto.setApplyUser((UserDto) RequestContext.get().getUser());
        publishApplyDto.setTargetType(Published.TargetType.Plugin);
        publishApplyDto.setTargetId(pluginDto.getId());
        // If publish scope is not selected, it means published items should be removed
        publishApplyDto.setChannels(pluginPublishApplyDto.getScope() == null ? new ArrayList<>() : List.of(Published.PublishChannel.System));
        publishApplyDto.setRemark(pluginPublishApplyDto.getRemark());
        publishApplyDto.setScope(pluginPublishApplyDto.getScope());
        publishApplyDto.setName(pluginDto.getName());
        publishApplyDto.setIcon(pluginDto.getIcon());
        publishApplyDto.setTargetConfig(pluginDto);
        publishApplyDto.setDescription(pluginDto.getDescription());
        publishApplyDto.setSpaceId(pluginDto.getSpaceId());
        Long applyId = publishApplicationService.publishApply(publishApplyDto);
        if (pluginPublishApplyDto.getScope() == Published.PublishScope.Space) {
            return ReqResult.create(ReqResult.SUCCESS, I18nUtil.systemMessage("Backend.Plugin.Publish.Success"), I18nUtil.systemMessage("Backend.Plugin.Publish.Success"));
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getPluginPublishAudit() == 0) {
            publishApplicationService.publish(applyId);
            return ReqResult.create(ReqResult.SUCCESS, I18nUtil.systemMessage("Backend.Plugin.Publish.Success"), I18nUtil.systemMessage("Backend.Plugin.Publish.Success"));
        }
        return ReqResult.create(ReqResult.SUCCESS, I18nUtil.systemMessage("Backend.Plugin.Publish.AuditPending"), I18nUtil.systemMessage("Backend.Plugin.Publish.AuditPending"));
    }

    private PluginDto checkPluginPermission(Long pluginId) {
        PluginDto pluginDto = pluginApplicationService.queryById(pluginId);
        if (pluginDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotFound);
        }
        spacePermissionService.checkSpaceUserPermission(pluginDto.getSpaceId());
        return pluginDto;
    }
}
