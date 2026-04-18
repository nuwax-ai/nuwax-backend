package com.xspaceagi.agent.core.infra.component.workflow;

import com.xspaceagi.agent.core.infra.code.CodeExecuteService;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.table.TableExecutor;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.RedisUtil;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.memory.ChatMemory;

@Setter
@Getter
public class WorkflowContextServiceHolder {

    private ChatMemory chatMemory;
    private WorkflowExecutor workflowExecutor;
    private ModelInvoker modelInvoker;
    private KnowledgeBaseSearcher knowledgeBaseSearcher;
    private McpExecutor mcpExecutor;
    private PluginExecutor pluginExecutor;
    private TableExecutor tableExecutor;
    private RedisUtil redisUtil;
    private CodeExecuteService codeExecuteService;
    private HttpClient httpClient;
    private IMemoryRpcService iMemoryRpcService;
}
