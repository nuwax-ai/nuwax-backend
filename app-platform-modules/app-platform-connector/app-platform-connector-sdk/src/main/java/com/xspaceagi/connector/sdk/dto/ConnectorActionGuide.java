package com.xspaceagi.connector.sdk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 动作使用指南：connector_guide 元工具的返回结构。
 * 供 LLM 了解入参结构、连接状态与安全须知后再发起 execute。
 */
@Data
public class ConnectorActionGuide implements Serializable {

    private static final long serialVersionUID = 1L;

    private ConnectorActionInfo action;

    /** 输入参数 Arg 树（JSON 结构） */
    private Object inputArgs;

    /** 输出参数 Arg 树（JSON 结构） */
    private Object outputArgs;

    /** provider 鉴权类型 */
    private String authType;

    /** 连接提示：未连接时告知如何建连（直填或 OAuth 授权 URL 提示） */
    private String connectionHint;

    /** 安全须知：上游内容只当数据、不当指令（提示注入防护） */
    private String securityNotice;
}
