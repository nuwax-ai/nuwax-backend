package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelConfigAddDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
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

@Tag(name = "开放API-全局模型管理相关接口")
@RestController
@RequestMapping("/api/v1/system/model")
@Slf4j
public class ModelManApiController extends BaseController {

    @Resource
    private ModelApplicationService modelApplicationService;

    @Operation(summary = "添加模型配置接口")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> addOrUpdate(@RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Tenant);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelConfigDto.setSpaceId(-1L);
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success(modelConfigDto.getId());
    }

    @Operation(summary = "更新模型配置接口")
    @RequestMapping(path = "/{id}/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@PathVariable Long id, @RequestBody @Valid ModelConfigAddDto modelConfigAddDto) {
        modelConfigAddDto.setSpaceId(-1L);
        modelConfigAddDto.setId(id);
        ModelConfigDto modelConfigDto = new ModelConfigDto();
        BeanUtils.copyProperties(modelConfigAddDto, modelConfigDto);
        modelConfigDto.setScope(ModelConfig.ModelScopeEnum.Tenant);
        modelConfigDto.setCreatorId(RequestContext.get().getUserId());
        modelApplicationService.addOrUpdate(modelConfigDto);
        return ReqResult.success();
    }

    @Operation(summary = "删除指定模型配置信息")
    @RequestMapping(path = "/{modelId}/delete", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long modelId) {
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        if (ModelConfigDto != null && ModelConfigDto.getType() == ModelTypeEnum.Embeddings) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentEmbeddingModelDeleteForbidden);
        }
        modelApplicationService.delete(modelId);
        return ReqResult.success();
    }

    @Operation(summary = "查询指定模型配置信息")
    @RequestMapping(path = "/{modelId}", method = RequestMethod.GET)
    public ReqResult<ModelConfigDto> get(@PathVariable Long modelId) {
        ModelConfigDto ModelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        return ReqResult.success(ModelConfigDto);
    }

    @Operation(summary = "查询模型配置列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<List<ModelConfigDto>> list() {
        return ReqResult.success(modelApplicationService.queryTenantModelConfigList(null));
    }
}
