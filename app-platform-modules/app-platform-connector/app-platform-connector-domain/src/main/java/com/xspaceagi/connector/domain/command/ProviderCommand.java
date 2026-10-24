package com.xspaceagi.connector.domain.command;

import lombok.Data;

import java.util.List;

/**
 * 创建/更新 provider 命令（JSON 字段以 Object 承载，落库前序列化）。
 */
@Data
public class ProviderCommand {

    private String service;

    private String displayName;

    private String description;

    private String icon;

    private String category;

    private List<String> tags;

    /** AuthTypeEnum code */
    private String authType;

    private String baseUrl;

    /** key 注入位置/OAuth 端点/scopes 等，JSON 对象 */
    private Object authConfig;

    /** OAuth App 模式：platform/byo */
    private String oauthAppMode;

    private Boolean proxyEnabled;
}
