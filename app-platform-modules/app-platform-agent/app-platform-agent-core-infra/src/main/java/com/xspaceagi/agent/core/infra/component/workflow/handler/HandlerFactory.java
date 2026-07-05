package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;

public class HandlerFactory {

    public static NodeHandler createNodeHandler(WorkflowNodeDto node) {
        return new ExceptionHandleWrappedNodeHandler(createNodeHandler0(node));
    }

    private static NodeHandler createNodeHandler0(WorkflowNodeDto node) {
        if (node.isVirtualExecute()) {
            return new VirtualExecuteHandler();
        }
        return switch (node.getType()) {
            case Start -> new StartNodeHandler();
            case End -> new EndNodeHandler();
            case Loop -> new LoopNodeHandler();
            case Plugin -> new WrappedNodeHandler(new PluginNodeHandler());
            case Mcp -> new WrappedNodeHandler(new McpNodeHandler());
            case Workflow -> new WrappedNodeHandler(new WorkflowAsNodeHandler());
            case LLM -> new WrappedNodeHandler(new LLMNodeHandler());
            case Output -> new WrappedNodeHandler(new ProcessOutputNodeHandler());
            case Code -> new WrappedNodeHandler(new CodeNodeHandler());
            case Knowledge -> new WrappedNodeHandler(new KnowledgeBaseNodeHandler());
            case KnowledgeInsert -> new WrappedNodeHandler(new KnowledgeInsertNodeHandler());
            case VariableAggregation -> new VariableAggregationNodeHandler();
            case Variable -> new VariableNodeHandler();
            case LongTermMemory -> new LongMemoryNodeHandler();
            case QA -> new WrappedNodeHandler(new QANodeHandler());
            case Condition -> new ConditionNodeHandler();
            case IntentRecognition -> new WrappedNodeHandler(new IntentRecognitionNodeHandler());
            case TextProcessing -> new TextProcessNodeHandler();
            case DocumentExtraction -> new DocumentExtractNodeHandler();
            case HTTPRequest -> new WrappedNodeHandler(new HttpNodeHandler());
            case TableDataAdd -> new WrappedNodeHandler(new TableDataAddNodeHandler());
            case TableDataQuery -> new WrappedNodeHandler(new TableDataQueryNodeHandler());
            case TableDataDelete -> new WrappedNodeHandler(new TableDataDeleteNodeHandler());
            case TableDataUpdate -> new WrappedNodeHandler(new TableDataUpdateNodeHandler());
            case TableSQL -> new WrappedNodeHandler(new TableCustomSQLNodeHandler());
            case LoopBreak, LoopContinue, LoopStart, LoopEnd -> new EmptyExecuteHandler();
            default -> new VirtualExecuteHandler();
        };
    }
}
