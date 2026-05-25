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
@TableName("bill_withdraw_application")
public class BillWithdrawApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    private Long userId;

    private BigDecimal amount;

    private BigDecimal fee;

    private BigDecimal actualAmount;

    private String status;

    private String rejectReason;

    private String paymentExtra;

    private Date created;

    private Date modified;
}
