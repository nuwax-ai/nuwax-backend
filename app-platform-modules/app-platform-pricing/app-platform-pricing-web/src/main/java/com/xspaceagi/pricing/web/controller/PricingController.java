package com.xspaceagi.pricing.web.controller;

import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.pricing.app.service.PricingAppService;
import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/pricing")
@Tag(name = "定价管理相关接口")
public class PricingController {

    @Resource
    private PricingAppService pricingAppService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IMcpApiService iMcpApiService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private IModelRpcService iModelRpcService;

    @PostMapping("/config/save")
    @Operation(summary = "资源-创建或更新定价配置")
    public ReqResult<Long> savePricingConfig(@RequestBody SavePricingConfigRequest request) {
        Assert.notNull(request, "request can not be null");
        Assert.notNull(request.getSpaceId(), "spaceId can not be null");
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
            spacePermissionService.checkSpaceUserPermission(modelInfo.getSpaceId());
            request.setPricingType(PricingTypeEnum.TIERED);
        } else if (request.getTargetType() == TargetTypeEnum.AGENT) {
            com.xspaceagi.agent.core.sdk.dto.ReqResult<List<AgentInfoDto>> listReqResult = iAgentRpcService.queryAgentInfoList(List.of(Long.parseLong(request.getTargetId())));
            if (listReqResult.getData() == null || listReqResult.getData().isEmpty()) {
                return ReqResult.error("Agent does not exist or no permission");
            }
            spacePermissionService.checkSpaceUserPermission(listReqResult.getData().get(0).getSpaceId());
        } else {
            spacePermissionService.checkSpaceUserPermission(request.getSpaceId());
        }
        if (request.getTargetType() == TargetTypeEnum.MCP) {
            McpDto deployedMcp = iMcpApiService.getDeployedMcp(Long.parseLong(request.getTargetId()), request.getSpaceId());
            if (deployedMcp == null) {
                return ReqResult.error("MCP is not published or no permission");
            }
            request.setPricingType(PricingTypeEnum.ONE_TIME);
        }
        if (request.getTargetType() == TargetTypeEnum.PLUGIN) {
            PluginInfoDto pluginInfoDto = iAgentRpcService.getPublishedPluginInfo(Long.parseLong(request.getTargetId()), request.getSpaceId()).getData();
            if (pluginInfoDto == null) {
                return ReqResult.error("Plugin is not published or no permission");
            }
            request.setPricingType(PricingTypeEnum.ONE_TIME);
        }
        if (request.getTargetType() == TargetTypeEnum.WORKFLOW) {
            WorkflowInfoDto workflowInfoDto = iAgentRpcService.getPublishedWorkflowInfo(Long.parseLong(request.getTargetId()), request.getSpaceId()).getData();
            if (workflowInfoDto == null) {
                return ReqResult.error("Workflow is not published or no permission");
            }
            request.setPricingType(PricingTypeEnum.ONE_TIME);
        }
        if (request.getTargetType() == TargetTypeEnum.SKILL) {
            SkillInfoDto data = iAgentRpcService.getPublishedSkillInfo(Long.parseLong(request.getTargetId()), request.getSpaceId()).getData();
            if (data == null) {
                return ReqResult.error("Skill is not published or no permission");
            }
        }
        return ReqResult.success(pricingAppService.savePricingConfig(request));
    }

    //删除资源定价
    @PostMapping("/config/{id}/delete")
    @Operation(summary = "资源-删除定价配置")
    public ReqResult<Void> deletePricingConfig(@PathVariable Long id) {
        Assert.notNull(id, "id can not be null");
        PricingConfigDTO pricingConfigDTO = pricingAppService.queryPricingConfig(id);
        if (pricingConfigDTO == null) {
            return ReqResult.error("Resource pricing does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(pricingConfigDTO.getSpaceId());
        pricingAppService.deletePricingConfig(id);
        return ReqResult.success();
    }

    @PostMapping("/config/list")
    @Operation(summary = "资源-查询定价配置列表")
    public ReqResult<List<PricingConfigDTO>> listPricingConfigs(@RequestBody PricingConfigQueryRequest query) {
        query.setTenantId(RequestContext.get().getTenantId());
        Assert.notNull(query, "query can not be null");
        Assert.notNull(query.getSpaceId(), "spaceId can not be null");
        spacePermissionService.checkSpaceUserPermission(query.getSpaceId());
        return ReqResult.success(pricingAppService.listPricingConfigs(query));
    }

    /**
     * 查询目标对象定价配置信息，该接口不需要权限限制
     */
    @PostMapping("/config/query")
    @Operation(summary = "查询目标对象定价配置")
    public ReqResult<PricingConfigDTO> queryPricingInfo(@RequestBody QueryPricingInfoRequest query) {
        Assert.notNull(query, "query can not be null");
        Assert.notNull(query.getTargetType(), "targetType can not be null");
        Assert.notNull(query.getTargetId(), "targetId can not be null");
        query.setTenantId(RequestContext.get().getTenantId());
        PricingConfigDTO pricingConfigDTO = pricingAppService.queryPricingInfo(query);
        if (pricingConfigDTO == null) {
            return ReqResult.success(null);
        }
        return ReqResult.success(pricingConfigDTO);
    }

    @PostMapping("/model-tier/add")
    @Operation(summary = "模型-新增价格档位")
    public ReqResult<Long> addModelPriceTier(@RequestBody ModelPriceTierDTO dto) {
        Assert.notNull(dto, "dto can not be null");
        Assert.notNull(dto.getModelId(), "modelId can not be null");
        Assert.notNull(dto.getContextLength(), "contextLength can not be null");
        Assert.notNull(dto.getInputPrice(), "inputPrice can not be null");
        Assert.notNull(dto.getOutputPrice(), "outputPrice can not be null");
        Long spaceId = checkModelPermission(dto.getModelId()).getSpaceId();
        dto.setSpaceId(spaceId);
        return ReqResult.success(pricingAppService.addModelPriceTier(dto));
    }

    @PostMapping("/model-tier/update")
    @Operation(summary = "模型-修改价格档位")
    public ReqResult<Boolean> updateModelPriceTier(@RequestBody ModelPriceTierDTO dto) {
        Assert.notNull(dto, "dto can not be null");
        Assert.notNull(dto.getId(), "id can not be null");
        Long spaceId = checkModelPermission(dto.getModelId()).getSpaceId();
        dto.setSpaceId(spaceId);
        return ReqResult.success(pricingAppService.updateModelPriceTier(dto));
    }

    @PostMapping("/model-tier/{id}/delete")
    @Operation(summary = "模型-删除价格档位")
    public ReqResult<Boolean> deleteModelPriceTier(@PathVariable Long id) {
        ModelPriceTierDTO modelPriceTierDTO = pricingAppService.queryModelPriceTier(id);
        if (modelPriceTierDTO == null) {
            return ReqResult.error("Model price tier does not exist");
        }
        checkModelPermission(modelPriceTierDTO.getModelId());
        return ReqResult.success(pricingAppService.deleteModelPriceTier(id));
    }

    @GetMapping("/model-tier/list")
    @Operation(summary = "模型-查询价格档位列表")
    public ReqResult<List<ModelPriceTierDTO>> listModelPriceTiers(@RequestParam Long modelId) {
        checkModelPermission(modelId);
        return ReqResult.success(pricingAppService.listModelPriceTiers(modelId));
    }

    private ModelInfoDto checkModelPermission(Long modelId) {
        ModelInfoDto modelInfo = iModelRpcService.getModelInfo(modelId);
        if (modelInfo == null) {
            throw new BizException("Model does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(modelInfo.getSpaceId());
        return modelInfo;
    }
}
