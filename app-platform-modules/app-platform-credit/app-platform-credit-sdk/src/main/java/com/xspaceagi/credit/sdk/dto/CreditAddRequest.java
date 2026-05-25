package com.xspaceagi.credit.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "用户积分批次添加请求")
public class CreditAddRequest extends CreditRequest {

    @Schema(description = "过期时间，为空表示永不过期")
    private Date expireTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "扩展字段", hidden = true)
    private Map<String, Object> extra;
}
