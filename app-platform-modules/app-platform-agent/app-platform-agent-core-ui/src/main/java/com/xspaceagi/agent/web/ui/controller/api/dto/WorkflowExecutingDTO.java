package com.xspaceagi.agent.web.ui.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class WorkflowExecutingDTO {

    @Schema(description = "事件类型")
    private EventType type;
    @Schema(description = "事件数据")
    private Object data;
    @Schema(description = "错误信息")
    private Map<String, Object> error;

    public enum EventType {
        Heartbeat,
        FinalResult,
    }
}
