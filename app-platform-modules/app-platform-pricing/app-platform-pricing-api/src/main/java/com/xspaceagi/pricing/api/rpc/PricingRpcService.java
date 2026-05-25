package com.xspaceagi.pricing.api.rpc;

import com.xspaceagi.pricing.app.service.PricingAppService;
import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

@Slf4j
@Service
public class PricingRpcService implements IPricingRpcService {

    @Resource
    private PricingAppService pricingAppService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public Long savePricingConfig(SavePricingConfigRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return pricingAppService.savePricingConfig(request);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.savePricingConfig(request);
    }

    @Override
    public List<PricingConfigDTO> listPricingConfigs(PricingConfigQueryRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return pricingAppService.listPricingConfigs(request);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.listPricingConfigs(request);
    }

    @Override
    public List<PricingConfigDTO> listPricingConfigs(TargetTypeEnum targetType, List<String> targetIds) {
        if (RequestContext.get() == null) {
            return TenantFunctions.callWithIgnoreCheck(() -> pricingAppService.listPricingConfigs(targetType, targetIds));
        }
        return pricingAppService.listPricingConfigs(targetType, targetIds);
    }

    @Override
    public PriceEstimate estimatePrice(Long tenantId, Long userId, List<PriceEstimate.EstimateTarget> estimateTargets) {
        if (RequestContext.get() == null || RequestContext.get().getUser() == null) {
            try {
                RequestContext.setThreadTenantId(tenantId);
                RequestContext.get().setUserId(userId);
                RequestContext.get().setTenantId(tenantId);
                if (RequestContext.get().getUser() == null) {
                    UserDto userDto = userApplicationService.queryById(userId);
                    RequestContext.get().setUser(userDto);
                    RequestContext.get().setLangMap(userDto.getLangMap());
                }
                return pricingAppService.estimatePrice(tenantId, userId, estimateTargets);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.estimatePrice(tenantId, userId, estimateTargets);
    }

    @Override
    public Long addModelPriceTier(ModelPriceTierDTO dto) {
        Assert.notNull(dto, "Request cannot be null");
        return pricingAppService.addModelPriceTier(dto);
    }

    @Override
    public boolean updateModelPriceTier(ModelPriceTierDTO dto) {
        Assert.notNull(dto, "Request cannot be null");
        Assert.notNull(dto.getId(), "Tier ID cannot be empty");
        return pricingAppService.updateModelPriceTier(dto);
    }

    @Override
    public boolean deleteModelPriceTier(Long tenantId, Long id) {
        Assert.notNull(id, "Tier ID cannot be empty");
        return pricingAppService.deleteModelPriceTier(id);
    }

    @Override
    public List<ModelPriceTierDTO> listModelPriceTiers(Long tenantId, Long modelId) {
        Assert.notNull(modelId, "Model ID cannot be empty");
        return pricingAppService.listModelPriceTiers(modelId);
    }

    @Override
    public TrialRecordDTO updateTrialCount(UpdateTrialCountRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return pricingAppService.updateTrialCount(request);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.updateTrialCount(request);
    }

    @Override
    public TrialRecordDTO getTrialCount(UpdateTrialCountRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return pricingAppService.getTrialCount(request);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.getTrialCount(request);
    }

    @Override
    public PricingConfigDTO queryPricingInfo(QueryPricingInfoRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return pricingAppService.queryPricingInfo(request);
            } finally {
                RequestContext.remove();
            }
        }
        return pricingAppService.queryPricingInfo(request);
    }
}
