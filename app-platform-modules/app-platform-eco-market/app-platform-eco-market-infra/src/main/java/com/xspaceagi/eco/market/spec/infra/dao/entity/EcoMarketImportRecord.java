package com.xspaceagi.eco.market.spec.infra.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 生态市场导入记录
 */
@Data
@TableName("eco_market_import_record")
public class EcoMarketImportRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long userId;

    private Long spaceId;

    private String targetType;

    private Long targetId;

    private String ecoTargetId;

    private LocalDateTime created;

    private LocalDateTime modified;
}
