package com.xspaceagi.connector.sdk.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 连接器（provider）摘要：供 Agent 发现型元工具使用。
 * 只含目录信息，不含任何凭证。
 */
@Data
public class ConnectorProviderInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String service;

    private String displayName;

    /**
     * 连接器介绍。写的是「连上之后怎么把凭证用起来」这类操作指引，不是给人看的营销文案——
     * 发现型元工具是 Agent 认识一个连接器的第一站，介绍不在这里出现，Agent 就只能看到
     * 名字和认证方式，连完不知道该调什么、该往哪个环境变量里塞令牌。
     */
    private String description;

    private String category;

    private String icon;

    /** api_key/oauth2/bearer/custom/no_auth */
    private String authType;

    private List<String> tags;

    /** 当前用户是否已建立可用连接 */
    private boolean connected;
}
