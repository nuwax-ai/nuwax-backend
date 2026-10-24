package com.xspaceagi.connector.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * OAuth App 配置（platform 公共 App + byo 用户自带 双轨）。
 */
@Data
@TableName("connector_oauth_config")
public class ConnectorOauthConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String providerService;

    /** platform 官方 App / byo 用户自带 */
    private String scopeType;

    /** 0=平台公共 App；byo 时为用户 id */
    private Long ownerId;

    private String clientId;

    /** client_secret 加密存储（enc:v1:） */
    private String clientSecretEnc;

    private String authUrl;

    private String tokenUrl;

    /** 申请的 scope 列表（JSON 数组字符串） */
    private String scopes;

    private Date created;

    private Date modified;
}
