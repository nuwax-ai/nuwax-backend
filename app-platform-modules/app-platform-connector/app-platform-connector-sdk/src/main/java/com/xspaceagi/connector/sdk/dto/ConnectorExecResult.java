package com.xspaceagi.connector.sdk.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * SDK 侧执行结果信封（与模块内 ExecResultDto 同构，避免领域类型出边界）。
 * meta.connectionProfile 是唯一允许带给 LLM 的连接身份信息。
 */
@Data
public class ConnectorExecResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;

    private String message;

    private Object data;

    private String errorCode;

    private Map<String, Object> meta = new HashMap<>();
}
