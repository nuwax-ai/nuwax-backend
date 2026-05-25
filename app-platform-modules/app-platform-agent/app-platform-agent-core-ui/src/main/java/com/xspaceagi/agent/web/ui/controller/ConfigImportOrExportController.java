package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.TemplateExportOrImportService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ExportTemplateDto;
import com.xspaceagi.agent.core.adapter.dto.ImportTemplateResultDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

import static com.xspaceagi.agent.core.adapter.repository.entity.Published.TargetType.*;
import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "配置模板导入导出接口")
@RestController
@RequestMapping("/api/template")
@Slf4j
public class ConfigImportOrExportController {

    @Resource
    private TemplateExportOrImportService templateExportOrImportService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Operation(summary = "导出配置接口，支持Agent、Workflow、Plugin、Table")
    @RequestMapping(path = "/export/{targetType}/{targetId}", method = RequestMethod.GET, produces = "application/octet-stream")
    public byte[] export(@PathVariable Published.TargetType targetType, @PathVariable Long targetId, HttpServletResponse response) {
        // 根据导出类型校验资源码权限
        String resourceCode = null;
        if (targetType == Agent) {
            resourceCode = AGENT_EXPORT.getCode();
        } else if (targetType == Plugin || targetType == Workflow || targetType == Table) {
            resourceCode = COMPONENT_LIB_EXPORT.getCode();
        }
        if (resourceCode != null) {
            sysUserPermissionCacheService.checkResourcePermissionAny(RequestContext.get().getUserId(), List.of(resourceCode));
        }

        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(targetId);
            if (agentConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
            }
            checkPermission(agentConfigDto.getCreatorId(), agentConfigDto.getSpaceId());
        }
        if (targetType == Published.TargetType.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryById(targetId);
            if (pluginDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotFound);
            }
            checkPermission(pluginDto.getCreatorId(), pluginDto.getSpaceId());
        }

        if (targetType == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(targetId);
            if (workflowConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFoundSimple);
            }
            checkPermission(workflowConfigDto.getCreatorId(), workflowConfigDto.getSpaceId());
        }
        if (targetType == Published.TargetType.Table) {
            DorisTableDefineRequest request = new DorisTableDefineRequest();
            request.setTableId(targetId);
            TableDefineVo dorisTableDefinitionVo;
            try {
                dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(request);
            } catch (Exception e) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTableSchemaQueryFailed);
            }
            checkPermission(dorisTableDefinitionVo.getCreatorId(), dorisTableDefinitionVo.getSpaceId());
        }
        ExportTemplateDto exportTemplateDto = templateExportOrImportService.queryTemplateConfig(targetType, targetId);
        if (exportTemplateDto.getTemplateConfig() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentExportFileGenerationFailed);
        }
        exportTemplateDto.setSpaceId(null);
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(exportTemplateDto.getName(), Charset.forName("UTF-8")) + "." + targetType.name().toLowerCase());
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        return JSON.toJSONString(exportTemplateDto).getBytes(Charset.forName("UTF-8"));
    }

    private void checkPermission(Long creatorId, Long spaceId) {
        try {
            spacePermissionService.checkSpaceAdminPermission(spaceId);
        } catch (Exception e) {
            spacePermissionService.checkSpaceUserPermission(spaceId);
            if (!RequestContext.get().getUserId().equals(creatorId)) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
    }

    //导入配置
    @Operation(summary = "导入配置接口")
    @PostMapping("/import/{spaceId}")
    public ReqResult<ImportTemplateResultDto> importTemplateConfig(@RequestParam("file") MultipartFile file, @PathVariable Long spaceId) throws IOException {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        // 根据导入类型控制资源码权限（Agent/Workflow/Plugin 导入配置）
        if (file.isEmpty()) {
            return ReqResult.error("Please select a file to upload");
        }
        String importTemplateConfig = new String(file.getBytes(), Charset.forName("UTF-8"));
        ExportTemplateDto exportTemplateDto = JSON.parseObject(importTemplateConfig, ExportTemplateDto.class);
        if (exportTemplateDto == null || exportTemplateDto.getTemplateConfig() == null || exportTemplateDto.getType() == null || Published.TargetType.valueOf(exportTemplateDto.getType()) == null) {
            return ReqResult.error("请上传正确的模板文件");
        }

        Published.TargetType targetType = Published.TargetType.valueOf(exportTemplateDto.getType());
        String resourceCode = null;
        if (targetType == Agent) {

            // 检查用户创建的智能体数量
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
            userDataPermission.checkMaxAgentCount(agentApplicationService.countUserCreatedAgent(RequestContext.get().getUserId()).intValue());

            resourceCode = AGENT_IMPORT.getCode();
        } else if (targetType == Workflow || targetType == Plugin || targetType == Knowledge || targetType == Table) {
            resourceCode = COMPONENT_LIB_IMPORT.getCode();
        }
        if (resourceCode != null) {
            sysUserPermissionCacheService.checkResourcePermissionAny(
                    RequestContext.get().getUserId(),
                    java.util.List.of(resourceCode)
            );
        }

        Long aLong = templateExportOrImportService.importTemplateConfig((UserDto) RequestContext.get().getUser(), spaceId, targetType, exportTemplateDto.getTemplateConfig().toString());
        ImportTemplateResultDto importTemplateResultDto = new ImportTemplateResultDto();
        importTemplateResultDto.setTargetId(aLong);
        importTemplateResultDto.setTargetType(targetType);
        return ReqResult.success(importTemplateResultDto);
    }
}
