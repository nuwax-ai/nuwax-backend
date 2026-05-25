package com.xspaceagi.pay.sdk.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PayConfigResponse {
    private BigDecimal payRate;
}
