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
@TableName("credit_package")
public class CreditPackage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String packageName;

    private BigDecimal creditAmount;

    private BigDecimal price;

    private Integer sort;

    private Integer status;

    private Date created;

    private Integer period;

    private Date modified;

    private String remark;
}
