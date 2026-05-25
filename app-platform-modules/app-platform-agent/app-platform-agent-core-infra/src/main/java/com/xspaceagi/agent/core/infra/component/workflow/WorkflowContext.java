package com.xspaceagi.agent.core.infra.component.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecuteResult;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.enums.NodeExecuteStatus;
import com.xspaceagi.system.sdk.common.TraceContext;
import lombok.Data;
import lombok.ToString;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Data
@ToString
public class WorkflowContext implements Serializable {

    private String requestId;

    private TraceContext traceContext;

    private AgentContext agentContext;

    private WorkflowConfigDto workflowConfig;

    private Map<String, Object> params;

    //测试试运行节点使用
    private Map<String, Object> testParams;

    //节点执行入参
    private Map<String, List<Map<String, Object>>> nodeExecuteInputMap;

    // 各个节点执行结果信息
    private Map<String, NodeExecuteResult> nodeExecuteResultMap;

    // 各个节点执行状态
    private Map<String, NodeExecuteStatus> nodeExecuteStatusMap;

    // 各个节点可能的中间变量值
    private Map<String, Map<String, Object>> nodeVariableValueMap;

    // 正在执行的循环节点
    private Map<String, LoopNodeExecutingDto> executingLoopNodeMap;

    private Long startTime;

    private Long endTime;

    private Consumer<NodeExecutingDto> nodeExecutingConsumer;

    //结束节点最终输出的内容（可选）
    private String endNodeContent;

    //结束节点配置的是否需要流式输出（可选）
    private Boolean endNodeRequireStreamOut;

    private Long originalWorkflowId;

    //工作流作为节点时，相关联的上级节点ID列表，用于缓存数据时生成key
    private String workflowPreIds;

    //工作流作为节点时，相关联的循环节点ID列表，用于缓存数据时生成key
    private String workflowLoopNodeIndex;

    // 相同requestId时是否使用缓存的结果，解决问答场景
    private boolean useResultCache;

    private boolean asyncExecute;

    private String asyncReplyContent;

    private String from;

    private WorkflowContextServiceHolder workflowContextServiceHolder;

    public Map<String, List<Map<String, Object>>> getNodeExecuteInputMap() {
        if (nodeExecuteInputMap == null) {
            nodeExecuteInputMap = new ConcurrentHashMap<>();
        }
        return nodeExecuteInputMap;
    }


    public Map<String, NodeExecuteResult> getNodeExecuteResultMap() {
        return nodeExecuteResultMap == null ? nodeExecuteResultMap = Collections.synchronizedMap(new LinkedHashMap<>()) : nodeExecuteResultMap;
    }

    public Map<String, NodeExecuteStatus> getNodeExecuteStatusMap() {
        if (nodeExecuteStatusMap == null) {
            nodeExecuteStatusMap = new ConcurrentHashMap<>();
        }
        return nodeExecuteStatusMap;
    }

    public Map<String, Map<String, Object>> getNodeVariableValueMap() {
        return nodeVariableValueMap == null ? nodeVariableValueMap = Collections.synchronizedMap(new LinkedHashMap<>()) : nodeVariableValueMap;
    }

    public Map<String, LoopNodeExecutingDto> getExecutingLoopNodeMap() {
        return executingLoopNodeMap == null ? executingLoopNodeMap = Collections.synchronizedMap(new LinkedHashMap<>()) : executingLoopNodeMap;
    }

    public AgentContext getCopiedAgentContext() {
        AgentContext agentContext0 = new AgentContext();
        BeanUtils.copyProperties(this.agentContext, agentContext0);
        agentContext0.setContextMessages(null);
        return agentContext0;
    }
}
