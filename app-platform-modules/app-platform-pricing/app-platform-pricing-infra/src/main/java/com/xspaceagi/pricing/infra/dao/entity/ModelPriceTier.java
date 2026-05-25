package com.xspaceagi.pricing.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("model_price_tier")
public class ModelPriceTier {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long modelId;

    private Integer contextLength;

    private BigDecimal inputPrice;

    private BigDecimal outputPrice;

    private BigDecimal cachePrice;

    @TableField("_tenant_id")
    private Long tenantId;

    private Date created;

    private Date modified;
}
