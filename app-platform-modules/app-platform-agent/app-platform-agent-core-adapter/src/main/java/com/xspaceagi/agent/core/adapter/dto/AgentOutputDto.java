package com.xspaceagi.agent.core.adapter.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentOutputDto implements Serializable {

    private String requestId;

    private EventTypeEnum eventType;

    private String error;

    private Object data;

    private boolean completed;

    public enum EventTypeEnum {
        // 执行过程
        PROCESSING,

        // 过程消息输出
        PROCESSING_MESSAGE,
        // 输出消息
        MESSAGE,
        // 最终统计等消息
        FINAL_RESULT,

        //  心跳
        HEART_BEAT,
        // 异常信息
        ERROR,

        // ACP 权限申请（跨端审批）
        ACP_REQUEST_PERMISSION
    }
}
