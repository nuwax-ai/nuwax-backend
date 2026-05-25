package com.xspaceagi.agent.core.infra.component.model;

import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallResult;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecutingDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Data
public class ModelContext implements Serializable {

    private String requestId;

    private String traceId;

    private String conversationId;

    @Schema(description = "智能体上下文")
    private AgentContext agentContext;

    @Schema(description = "链路追踪信息")
    private TraceContext traceContext;

    @Schema(description = "模型配置")
    private ModelConfigDto modelConfig;

    @Schema(description = "模型执行配置")
    private ModelCallConfigDto modelCallConfig;

    @Schema(description = "组件执行结果")
    private List<ComponentExecuteResult> componentExecuteResults;

    @Schema(description = "模型执行结果")
    private ModelCallResult modelCallResult;

    @Schema(description = "组件执行跟踪")
    private Consumer<ComponentExecutingDto> componentExecutingConsumer;

    @Schema(description = "模型加载的工作流中的节点执行跟踪")
    private Consumer<NodeExecutingDto> nodeExecutingConsumer;

    private int retryCount;

    private boolean hasReasoningContent;

    private boolean fromAgent;

    public ModelCallResult getModelCallResult() {
        if (modelCallResult == null) {
            modelCallResult = new ModelCallResult();
        }
        return modelCallResult;
    }

    public List<ComponentExecuteResult> getComponentExecuteResults() {
        return componentExecuteResults == null ? componentExecuteResults = new ArrayList<>() : componentExecuteResults;
    }

    public void setHasReasoningContent(boolean hasReasoningContent) {
        if (this.hasReasoningContent) {
            return;
        }
        this.hasReasoningContent = hasReasoningContent;
    }
}
