package com.xspaceagi.subscription.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.subscription.spec.enums.BizTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_subscription")
public class UserSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long planId;

    private BizTypeEnum bizType;

    private String bizId;

    private Integer period;

    private Date startTime;

    private Date endTime;

    private Integer status;

    private Integer callUsedCount;

    private Date nextResetTime;

    @TableField("_tenant_id")
    private Long tenantId;

    private String extra;

    private Date created;

    private Date modified;
}
