package com.xspaceagi.connector.domain.command;

import lombok.Data;

import java.util.Map;

/**
 * 直填凭证建连接命令（api_key/bearer/custom/no_auth，设计文档 §7）。
 */
@Data
public class CreateConnectionCommand {

    private Long spaceId;

    private String providerService;

    /** 用户起的别名 */
    private String name;

    /**
     * 凭证字段明文（如 {"apiKey":"..."} / {"token":"..."} / 自定义多字段）。
     * 仅在入 Vault 前短暂存在，绝不落日志、不出模块。
     */
    private Map<String, String> fields;
}
