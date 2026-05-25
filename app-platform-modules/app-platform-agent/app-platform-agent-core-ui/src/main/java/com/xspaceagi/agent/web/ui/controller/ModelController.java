package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelConfigAddDto;
import com.xspaceagi.agent.core.adapter.dto.ModelQueryDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.infra.modelproviders.ModelProviderParser;
import com.xspaceagi.agent.core.infra.modelproviders.vo.ModelProviderVo;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
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

@Tag(name = "模型配置相关接口")
@RestController
@RequestMapping("/api/model")
@Slf4j
public class ModelController {

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @RequireResource({COMPONENT_LIB_CREATE, COMPONENT_LIB_MODIFY})
    @Operation(summary = "在空间中添加或更新模型配置接口")
    @RequestMapping(path = "/save", method = RequestMethod.POST)
    public ReqResult<Void> addOrUpdate(@RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        Assert.notNull(modelConfigAddDto.getSpaceId(), "Invalid spaceId");
        //新增时校验空间权限
        spacePermissionService.checkSpaceUserPermission(modelConfigAddDto.getSpaceId());
        if (modelConfigAddDto.getId() != null) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(modelConfigAddDto.getId());
            if (modelConfigDto == null || !modelConfigDto.getSpaceId().equals(modelConfigAddDto.getSpaceId())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOpenapiModelIdInvalid);
            }
        }
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Space);
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success();
    }

    @RequireResource(COMPONENT_LIB_DELETE)
    @Operation(summary = "删除指定模型配置信息")
    @RequestMapping(path = "/{modelId}/delete", method = RequestMethod.GET)
    public ReqResult<Void> delete(@PathVariable Long modelId) {
        modelApplicationService.checkModelManagePermission(modelId);
        modelApplicationService.delete(modelId);
        return ReqResult.success();
    }

    @RequireResource({COMPONENT_LIB_QUERY_DETAIL, MODEL_MANAGE_MODIFY})
    @Operation(summary = "查询指定模型配置信息")
    @RequestMapping(path = "/{modelId}", method = RequestMethod.GET)
    public ReqResult<ModelConfigDto> get(@PathVariable Long modelId) {
        modelApplicationService.checkModelManagePermission(modelId);
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        return ReqResult.success(ModelConfigDto);
    }

    @RequireResource({COMPONENT_LIB_QUERY_LIST, SYSTEM_SETTING_MODEL_DEFAULT})
    @Operation(summary = "查询可使用模型列表接口")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ModelConfigDto>> list(@RequestBody ModelQueryDto modelQueryDto) {
        if (modelQueryDto.getSpaceId() != null) {
            spacePermissionService.checkSpaceUserPermission(modelQueryDto.getSpaceId());
        }
        modelQueryDto.setEnabled(YesOrNoEnum.Y.getKey());
        List<ModelConfigDto> modelDtos = modelApplicationService.queryModelConfigList(modelQueryDto);
        return ReqResult.success(modelDtos);
    }

    @RequireResource(COMPONENT_LIB_QUERY_LIST)
    @Operation(summary = "查询指定空间下模型列表接口")
    @RequestMapping(path = "/list/space/{spaceId}", method = RequestMethod.POST)
    public ReqResult<List<ModelConfigDto>> querySpaceModelList(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<ModelConfigDto> modelDtos = modelApplicationService.queryModelConfigLisBySpaceId(spaceId);
        return ReqResult.success(modelDtos);
    }

    @Operation(summary = "测试模型连通性")
    @RequestMapping(path = "/test-connectivity", method = RequestMethod.POST)
    public ReqResult<Boolean> testConnectivity(@RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setNetworkType(ModelConfig.NetworkType.Internet);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Space);
        modelConfigDto.setTenantId(RequestContext.get().getTenantId());

        String response = modelApplicationService.testModelConnectivity(modelConfigDto, "Hi");

        if (response != null) {
            return ReqResult.error(response);
        }
        return ReqResult.success(true);
    }

    @Operation(summary = "模型厂商查询")
    @RequestMapping(path = "/providers", method = RequestMethod.GET)
    public ReqResult<List<ModelProviderVo>> getModelProviders() {
        List<ModelProviderVo> modelProviderVos = ModelProviderParser.loadAll();
        I18nUtil.replaceSystemMessage(modelProviderVos);
        return ReqResult.success(modelProviderVos);
    }

    @Operation(summary = "查询我的模型权限,tab=System|Space")
    @RequestMapping(path = "/my", method = RequestMethod.GET)
    public ReqResult<List<ModelConfigDto>> getMySystemModels(@RequestParam String tab) {
        List<ModelConfigDto> models = modelApplicationService.getMySystemModels(RequestContext.get().getUserId(), tab);
        return ReqResult.success(models);
    }
}
