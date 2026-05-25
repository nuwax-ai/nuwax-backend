package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PriceEstimate {
    private boolean pass;
    private String message;
    private TargetTypeEnum targetType;
    private String targetId;
    private Object subscription;
    private Long resourceBillUserId;//临时传递数据
    private List<PricingConfigDTO> pricingConfigs;
    private boolean trial;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class EstimateTarget {

        private TargetTypeEnum targetType;
        private String targetId;
    }
}