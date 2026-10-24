package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 外部登录方式（身份源）配置
 */
@Data
@TableName(value = "auth_idp_config", autoResultMap = true)
public class AuthIdpConfig {

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
     * 类型：CAS / OAUTH2 / WECHAT
     * @see com.xspaceagi.system.spec.enums.AuthIdpTypeEnum
     */
    private String type;

    /**
     * 配置名称（登录页展示）
     */
    private String name;

    /**
     * 登录图标
     */
    private String icon;

    /**
     * 状态：1-启用、0-停用
     */
    private Integer enabled;

    /**
     * 未登录自动跳转：1-是（租户内全局唯一，服务层保证）
     */
    private Integer autoRedirect;

    /**
     * 自动注册/绑定：1-免中间页（已注册按邮箱/手机号自动绑定、未注册自动注册并绑定），
     * 0-进中间页由用户选择。开启即信任该 IdP 的邮箱/手机号
     */
    private Integer autoRegisterBind;

    /**
     * 类型相关配置 JSON
     */
    private String config;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
