package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class NodeConfigDto implements Serializable {

    private ContextPassingType contextPassingType;

    @Schema(description = "扩展字段，用于前端存储画布位置等相关配置")
    private Map<String, Object> extension;

    @Schema(description = "节点入参")
    private List<Arg> inputArgs;

    @Schema(description = "节点出参")
    private List<Arg> outputArgs;

    @Schema(description = "异常处理配置")
    private ExceptionHandleConfigDto exceptionHandleConfig;

    public enum ContextPassingType {
        Auto,
        Manual
    }
}
