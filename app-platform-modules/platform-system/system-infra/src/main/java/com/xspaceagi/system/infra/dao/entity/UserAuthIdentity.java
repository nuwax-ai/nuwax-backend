package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户外部身份绑定：外部身份（idp + external_id）与平台账号的关联，一个用户可绑定多个外部身份
 */
@Data
@TableName(value = "user_auth_identity", autoResultMap = true)
public class UserAuthIdentity {

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
     * 平台用户ID
     */
    private Long userId;

    /**
     * 身份源配置ID
     */
    private Long idpId;

    /**
     * 身份源类型，同 auth_idp_config.type
     */
    private String idpType;

    /**
     * 外部稳定标识：CAS user / OAuth2 sub·unionId / 微信 unionid(优先)或 openid
     */
    private String externalId;

    /**
     * 微信 unionid，跨应用（扫码/公众号/小程序）识别同一人
     */
    private String unionId;

    /**
     * 外部用户名/昵称
     */
    private String externalUserName;

    /**
     * 最近一次资料快照（脱敏后）
     */
    private String rawProfile;

    /**
     * 该身份最近登录时间
     */
    private Date lastLoginTime;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
