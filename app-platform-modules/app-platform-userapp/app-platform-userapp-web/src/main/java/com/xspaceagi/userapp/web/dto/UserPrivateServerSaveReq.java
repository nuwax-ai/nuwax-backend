package com.xspaceagi.userapp.web.dto;

import lombok.Data;

/**
 * 私服登记/更新请求（仅访问信息；docker 环境由用户自行安装。更新时 id 必填）
 */
@Data
public class UserPrivateServerSaveReq {

    private Long id;

    private String name;

    /** 协议：http/https */
    private String scheme;

    /** 服务器地址（IP或域名） */
    private String host;

    /** 管理端口（agent API） */
    private Integer agentPort;

    /** 交互流量端口（用户流量入口） */
    private Integer vncPort;

    /** 文件服务端口 */
    private Integer fileServerPort;

    /**
     * 平台与私服通信密钥（可选，仅更新生效；创建时平台自动生成）。
     * 传入则覆盖现有值，不传保持不变；长度上限 128
     */
    private String apiKey;
}
