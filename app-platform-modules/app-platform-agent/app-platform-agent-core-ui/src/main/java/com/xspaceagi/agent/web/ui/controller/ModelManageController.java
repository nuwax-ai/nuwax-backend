package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelConfigAddDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.dto.permission.SubjectTargetsDto;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "系统管理-模型管理相关接口")
@RestController
@RequestMapping("/api/system/model")
@Slf4j
public class ModelManageController extends BaseController {

    @Resource
    private ModelApplicationService modelApplicationService;
    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @RequireResource({MODEL_MANAGE_ADD, MODEL_MANAGE_MODIFY})
    @Operation(summary = "添加或更新模型配置接口")
    @RequestMapping(path = "/save", method = RequestMethod.POST)
    public ReqResult<Void> addOrUpdate(@RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        modelConfigAddDto.setSpaceId(-1L);
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Tenant);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success();
    }

    @RequireResource(MODEL_MANAGE_MODIFY)
    @Operation(summary = "更新模型管控状态")
    @RequestMapping(path = "/{modelId}/accessControl/{status}", method = RequestMethod.POST)
    public ReqResult<Void> updateAccessControlStatus(@PathVariable Long modelId, @PathVariable Integer status) {
        modelApplicationService.updateAccessControlStatus(modelId, status);
        return ReqResult.success();
    }

    @RequireResource(MODEL_MANAGE_DELETE)
    @Operation(summary = "删除指定模型配置信息")
    @RequestMapping(path = "/{modelId}/delete", method = RequestMethod.GET)
    public ReqResult<Void> delete(@PathVariable Long modelId) {
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        if (ModelConfigDto != null && ModelConfigDto.getType() == ModelTypeEnum.Embeddings) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentEmbeddingModelDeleteForbidden);
        }
        modelApplicationService.delete(modelId);
        return ReqResult.success();
    }

    @RequireResource({MODEL_MANAGE_MODIFY})
    @Operation(summary = "查询指定模型配置信息")
    @RequestMapping(path = "/{modelId}", method = RequestMethod.GET)
    public ReqResult<ModelConfigDto> get(@PathVariable Long modelId) {
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        return ReqResult.success(ModelConfigDto);
    }

    @RequireResource(MODEL_MANAGE_QUERY_LIST)
    @Operation(summary = "查询模型配置列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<List<ModelConfigDto>> list(@RequestParam(required = false) Integer accessControl) {
        return ReqResult.success(modelApplicationService.queryTenantModelConfigList(accessControl));
    }

    @RequireResource(MODEL_MANAGE_ACCESS_CONTROL)
    @Operation(summary = "查询模型限制访问的对象")
    @RequestMapping(path = "/restriction-targets/{modelId}", method = RequestMethod.GET)
    public ReqResult<SubjectTargetsDto> getRestrictionTargets(@PathVariable Long modelId) {
        return ReqResult.success(sysSubjectPermissionApplicationService.listTargetsBySubject(
                PermissionSubjectTypeEnum.MODEL, modelId));
    }

    @RequireResource(MODEL_MANAGE_ACCESS_CONTROL)
    @Operation(summary = "绑定模型限制访问对象（全量覆盖）")
    @RequestMapping(path = "/bind-restriction-targets", method = RequestMethod.POST)
    public ReqResult<Void> bindRestrictionTargets(@RequestBody @Valid BindRestrictionTargetsDto bindDto) {
        modelApplicationService.checkModelManagePermission(bindDto.getSubjectId());
        sysSubjectPermissionApplicationService.bindRestrictionTargets(
                PermissionSubjectTypeEnum.MODEL, bindDto.getSubjectId(), bindDto, getUser());
        return ReqResult.success();
    }
}
