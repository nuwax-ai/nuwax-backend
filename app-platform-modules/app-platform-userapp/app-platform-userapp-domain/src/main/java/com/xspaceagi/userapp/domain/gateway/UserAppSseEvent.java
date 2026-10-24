package com.xspaceagi.userapp.domain.gateway;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 下游任务进度 SSE 流的单条事件
 */
@Data
@AllArgsConstructor
public class UserAppSseEvent {

    /** 事件ID（下游 seq，从 0 递增；可能缺失） */
    private String id;

    /** 事件名：building/log/build_ok/build_fail/completed/failed/cancelled/stream_lagged */
    private String event;

    /** 事件 JSON 载荷原文 */
    private String data;
}
