package com.xspaceagi.credit.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("user_credit")
public class UserCredit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String batchNo;

    private Integer creditType;

    private BigDecimal totalAmount;

    private BigDecimal usedAmount;

    private BigDecimal remainAmount;

    private Date expireTime;

    private Date created;

    private Date modified;

    private BigDecimal repaidAmount;

    private Integer repayStatus;

    private String remark;

    private Integer version;

    private String extra;
}
