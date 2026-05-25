package com.xspaceagi.pricing.web.controller;

import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.pricing.app.service.PricingAppService;
import com.xspaceagi.pricing.sdk.dto.ModelPriceTierDTO;
import com.xspaceagi.pricing.sdk.dto.PricingConfigDTO;
import com.xspaceagi.pricing.sdk.dto.PricingConfigQueryRequest;
import com.xspaceagi.pricing.sdk.dto.SavePricingConfigRequest;
import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.MODEL_MANAGE_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.MODEL_MANAGE_QUERY_LIST;

@Slf4j
@RestController
@RequestMapping("/api/system/pricing")
@Tag(name = "模型定价管理（管理端）")
public class PricingAdminController {

    @Resource
    private PricingAppService pricingAppService;

    @Resource
    private IModelRpcService iModelRpcService;

    @RequireResource(MODEL_MANAGE_QUERY_LIST)
    @PostMapping("/config/save")
    @Operation(summary = "资源-创建或更新定价配置")
    public ReqResult<Long> savePricingConfig(@RequestBody SavePricingConfigRequest request) {
        Assert.notNull(request, "request can not be null");
        Assert.notNull(request.getTargetId(), "targetId can not be null");
        Assert.notNull(request.getTargetType(), "targetType can not be null");
        Assert.notNull(request.getPricingType(), "pricingType can not be null");
        request.setTenantId(RequestContext.get().getTenantId());
        // 权限校验
        if (request.getTargetType() == TargetTypeEnum.MODEL) {
            ModelInfoDto modelInfo = iModelRpcService.getModelInfo(Long.parseLong(request.getTargetId()));
            if (modelInfo == null) {
                return ReqResult.error("Model does not exist or no permission");
            }
            request.setPricingType(PricingTypeEnum.TIERED);
        }
        return ReqResult.success(pricingAppService.savePricingConfig(request));
    }

    @RequireResource(MODEL_MANAGE_QUERY_LIST)
    @PostMapping("/config/{id}/delete")
    @Operation(summary = "删除定价配置")
    public ReqResult<Void> deletePricingConfig(@PathVariable Long id) {
        Assert.notNull(id, "id can not be null");
        PricingConfigDTO pricingConfigDTO = pricingAppService.queryPricingConfig(id);
        if (pricingConfigDTO == null) {
            return ReqResult.error("Resource pricing does not exist");
        }
        pricingAppService.deletePricingConfig(id);
        return ReqResult.success();
    }

    @RequireResource(MODEL_MANAGE_QUERY_LIST)
    @GetMapping("/model/config/list")
    @Operation(summary = "查询定价配置列表")
    public ReqResult<List<PricingConfigDTO>> listPricingConfigs() {
        PricingConfigQueryRequest query = new PricingConfigQueryRequest();
        query.setSpaceId(-1L);
        query.setTargetType(TargetTypeEnum.MODEL);
        return ReqResult.success(pricingAppService.listPricingConfigs(query));
    }

    @RequireResource(MODEL_MANAGE_MODIFY)
    @PostMapping("/model-tier/add")
    @Operation(summary = "新增模型价格档位")
    public ReqResult<Long> addModelPriceTier(@RequestBody ModelPriceTierDTO dto) {
        return ReqResult.success(pricingAppService.addModelPriceTier(dto));
    }

    @RequireResource(MODEL_MANAGE_MODIFY)
    @PostMapping("/model-tier/update")
    @Operation(summary = "修改模型价格档位")
    public ReqResult<Boolean> updateModelPriceTier(@RequestBody ModelPriceTierDTO dto) {
        return ReqResult.success(pricingAppService.updateModelPriceTier(dto));
    }

    @RequireResource(MODEL_MANAGE_MODIFY)
    @PostMapping("/model-tier/{id}/delete")
    @Operation(summary = "删除模型价格档位")
    public ReqResult<Boolean> deleteModelPriceTier(@PathVariable Long id) {
        return ReqResult.success(pricingAppService.deleteModelPriceTier(id));
    }

    @RequireResource(MODEL_MANAGE_QUERY_LIST)
    @GetMapping("/model-tier/list")
    @Operation(summary = "查询模型价格档位列表")
    public ReqResult<List<ModelPriceTierDTO>> listModelPriceTiers(@RequestParam Long modelId) {
        return ReqResult.success(pricingAppService.listModelPriceTiers(modelId));
    }
}
