package com.xspaceagi.bill.infra.dao.entity;

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
@TableName("bill_resource_stat")
public class BillResourceStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    private Long userId;

    private String type;

    private String targetType;

    private Long targetId;

    private String dt;

    private Long callCount;

    private Long callFailedCount;

    private BigDecimal creditAmount;

    private BigDecimal feeAmount;

    private Long cacheInputTokens;

    private Long inputTokens;

    private Long outputTokens;

    private String extra;

    private Date created;

    private Date modified;
}