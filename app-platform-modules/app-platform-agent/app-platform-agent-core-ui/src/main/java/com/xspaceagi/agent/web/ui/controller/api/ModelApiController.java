package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelConfigAddDto;
import com.xspaceagi.agent.core.adapter.dto.ModelQueryDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "开放API-空间模型配置相关接口")
@RestController
@RequestMapping("/api/v1")
@Slf4j
public class ModelApiController {

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Operation(summary = "添加模型配置接口")
    @RequestMapping(path = "/space/{spaceId}/model/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@PathVariable Long spaceId, @RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        //新增时校验空间权限
        spacePermissionService.checkSpaceUserPermission(spaceId);
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setSpaceId(spaceId);
        modelConfigDto.setId(null);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Space);
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success(modelConfigDto.getId());
    }

    @Operation(summary = "更新模型配置接口")
    @RequestMapping(path = "/model/{id}/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@PathVariable Long id, @RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        ModelConfigDto modelConfigDto0 = modelApplicationService.queryModelConfigById(id);
        if (modelConfigDto0 == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOpenapiModelIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(modelConfigDto0.getSpaceId());
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setId(id);
        modelConfigDto.setSpaceId(null);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Space);
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success();
    }

    @Operation(summary = "删除指定模型配置信息")
    @RequestMapping(path = "/model/{modelId}/delete", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long modelId) {
        modelApplicationService.checkModelManagePermission(modelId);
        modelApplicationService.delete(modelId);
        return ReqResult.success();
    }

    @Operation(summary = "查询指定模型配置信息")
    @RequestMapping(path = "/model/{modelId}", method = RequestMethod.GET)
    public ReqResult<ModelConfigDto> get(@PathVariable Long modelId) {
        modelApplicationService.checkModelManagePermission(modelId);
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        return ReqResult.success(ModelConfigDto);
    }

    @Operation(summary = "查询可使用模型列表接口")
    @RequestMapping(path = "/space/{spaceId}/model/list", method = RequestMethod.GET)
    public ReqResult<List<ModelConfigDto>> list(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<ModelConfigDto> modelDtos = modelApplicationService.queryModelConfigLisBySpaceId(spaceId);
        return ReqResult.success(modelDtos);
    }

}
