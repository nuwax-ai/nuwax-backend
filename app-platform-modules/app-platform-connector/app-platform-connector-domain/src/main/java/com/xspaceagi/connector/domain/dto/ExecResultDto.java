package com.xspaceagi.connector.domain.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一执行结果信封（设计文档 §4.4）。
 * <p>
 * 无论声明式/CUSTOM/代理路径，对外只返回该结构；Agent/LLM 只见 data 与 meta.connectionProfile，
 * 绝不含凭证。
 */
@Data
public class ExecResultDto {

    private boolean success;

    /** 人类可读说明（成功摘要或失败原因） */
    private String message;

    /** 上游返回的数据（经 http_spec 抽取后） */
    private Object data;

    /** 失败时的 ConnectorErrorCode code */
    private String errorCode;

    /** executionId / connectionProfile / durationMs / warnings 等 */
    private Map<String, Object> meta = new HashMap<>();

    public static ExecResultDto ok(Object data, String message) {
        ExecResultDto result = new ExecResultDto();
        result.setSuccess(true);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static ExecResultDto fail(String errorCode, String message) {
        ExecResultDto result = new ExecResultDto();
        result.setSuccess(false);
        result.setErrorCode(errorCode);
        result.setMessage(message);
        return result;
    }

    public ExecResultDto meta(String key, Object value) {
        this.meta.put(key, value);
        return this;
    }
}
