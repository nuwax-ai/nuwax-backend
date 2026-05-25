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
@TableName("pricing_config")
public class PricingConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String targetType;

    private String targetId;

    private String pricingType;

    private BigDecimal price;

    private Integer trialCount;

    private Integer status;

    private Long spaceId;

    @TableField("_tenant_id")
    private Long tenantId;

    private Date created;

    private Date modified;
}
