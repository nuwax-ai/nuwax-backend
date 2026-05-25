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
@TableName("credit_flow")
public class CreditFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String batchNo;

    private Integer creditType;

    private Integer operationType;

    private BigDecimal amount;

    private BigDecimal beforeAmount;

    private BigDecimal afterAmount;

    private String bizNo;

    private Date created;

    private String remark;
}
