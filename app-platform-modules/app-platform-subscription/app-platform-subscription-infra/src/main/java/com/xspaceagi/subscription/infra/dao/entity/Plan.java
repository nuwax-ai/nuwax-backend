package com.xspaceagi.subscription.infra.dao.entity;

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
@TableName("subscription_plan")
public class Plan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private BigDecimal price;

    private BigDecimal firstPrice;

    private Integer period;

    private BigDecimal creditAmount;

    private Integer callLimitCount;

    private Boolean functionOnly;

    private Boolean isHot;

    private Integer status;

    private String bizType;

    private String bizId;

    private String groupIds;

    private String extra;

    private Integer sort;

    @TableField("_tenant_id")
    private Long tenantId;

    private Date created;

    private Date modified;
}
