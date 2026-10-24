package com.xspaceagi.userapp.domain.model;

import java.util.Date;

import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

import lombok.Data;

/**
 * 用户私有部署服务器（登记一次，名下项目可引用部署）
 */
@Data
public class UserPrivateServerModel {

    private Long id;

    private Long tenantId;

    /** 登记用户（属主 */
    private Long ownerUserId;

    private String name;

    /** 协议：http/https */
    private String scheme;

    /** 服务器地址（IP或域名） */
    private String host;

    /** 管理端口 */
    private Integer agentPort;

    /** 交互流量端口（用户流量入口） */
    private Integer vncPort;

    /** 文件服务端口 */
    private Integer fileServerPort;

    /** 平台与私服 agent 通信密钥（x-api-key） */
    private String apiKey;

    private UserPrivateServerStatus status;

    private String version;

    private Date lastHealthCheck;

    private Date created;

    private Date modified;
}
