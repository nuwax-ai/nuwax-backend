package com.xspaceagi.agent.core.infra.component.model.dto;

import lombok.Data;

/**
 * 工具执行轨迹（组件工具专用，由 {@code ModelInvoker.componentExecute} 统一采集）。
 * <p>
 * 所有经 spring-ai 执行的组件工具（Plugin/Workflow/Knowledge/Mcp/Table 等）在调用时
 * 以 {@code role=assistant} + 结果以 {@code role=tool} 追加到 AgentContext.toolTraceAccum，
 * 由总控 MasterAgentLoop 在每次流式调用结束后收集并入执行链（toolChain），随会话变量持久化。
 * toolCallId 复用组件执行的 executeId，保证 "tool_use → tool_result" 严格配对。
 * </p>
 */
@Data
public class ToolExecRecord {

    /**
     * assistant（工具调用声明）/ tool（工具执行结果）
     */
    private String role;

    /**
     * 工具名（组件 functionName 或名称，用于与模型侧工具名对齐）
     */
    private String toolName;

    /**
     * 工具入参 JSON（role=assistant 时）
     */
    private String toolArgs;

    /**
     * 工具执行结果文本（role=tool 时）
     */
    private String text;

    /**
     * 配对 ID（复用组件执行的 executeId）
     */
    private String toolCallId;
}
