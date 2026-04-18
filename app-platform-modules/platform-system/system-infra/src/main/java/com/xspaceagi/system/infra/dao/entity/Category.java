package com.xspaceagi.system.infra.dao.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import lombok.Data;

/**
 * 分类管理
 */
@I18n(module = "Category")
@Data
@TableName(value = "category", autoResultMap = true)
public class Category {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;

    /**
     * 分类名称
     */
    @I18nField(field = "name")
    private String name;

    /**
     * 分类描述
     */
    @I18nField(field = "description")
    private String description;

    /**
     * 分类编码
     */
    @I18nField(field = "code", keyPrefix = true)
    private String code;

    /**
     * 分类类型：Agent、PageApp、Component
     */
    private String type;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
