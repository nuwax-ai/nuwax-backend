package com.xspaceagi.credit.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户积分扣减请求")
public class CreditDeductRequest extends CreditRequest {

    @Schema(description = "是否允许透支，默认false")
    private Boolean allowNegative = false;

    @Schema(description = "备注")
    private String remark;
}
