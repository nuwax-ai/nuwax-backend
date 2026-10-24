package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.Date;

import lombok.Data;

/**
 * 私有部署服务器信息
 */
@Data
public class UserPrivateServerDto implements Serializable {

    private Long id;

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

    /**
     * 平台与私服 agent 的通信密钥（x-api-key）
     */
    private String apiKey;

    /** 状态：unverified 未验证/online 在线/offline 离线 */
    private String status;

    private String version;

    private Date lastHealthCheck;

    private Date created;

    private Date modified;
}
