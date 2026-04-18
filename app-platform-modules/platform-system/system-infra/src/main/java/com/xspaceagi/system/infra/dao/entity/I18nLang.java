package com.xspaceagi.system.infra.dao.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 语言表
 */
@Data
@TableName(value = "i18n_lang", autoResultMap = true)
public class I18nLang {

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;

    /**
     * 语言名称，例如 简体中文
     */
    private String name;

    /**
     * 语言，中文：zh-cn，英文:en-us 等等
     */
    private String lang;

    /**
     * 语言状态，0 停用；1 启用
     */
    private Integer status;

    /**
     * 是否为默认语言，0 否；1 是
     */
    private Integer isDefault;

    /**
     * 排序，值越小越靠前
     */
    private Integer sort;

    /**
     * 更新时间
     */
    private Date modified;

    /**
     * 创建时间
     */
    private Date created;
}
