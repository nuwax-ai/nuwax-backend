package com.xspaceagi.connector.sdk.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 动作摘要：connector_search 元工具的返回单元。
 */
@Data
public class ConnectorActionInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long actionId;

    private String providerService;

    private String providerDisplayName;

    private String actionKey;

    private String name;

    private String description;

    private List<String> tags;

    /** 执行该动作是否需要连接（no_auth 的 provider 不需要） */
    private boolean requiresConnection;

    /** 当前用户对该 provider 是否已有可用连接 */
    private boolean connected;
}
