package com.xspaceagi.pricing.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "模型阶梯价格配置")
public class ModelPriceTierDTO implements Serializable {

    @Schema(description = "档位ID")
    private Long id;

    @Schema(description = "模型ID")
    private Long modelId;

    @Schema(description = "空间ID", hidden = true)
    private Long spaceId;

    @Schema(description = "上下文长度（如32代表32k）")
    private Integer contextLength;

    @Schema(description = "输入价格")
    private BigDecimal inputPrice;

    @Schema(description = "输出价格")
    private BigDecimal outputPrice;

    @Schema(description = "缓存价格")
    private BigDecimal cachePrice;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
