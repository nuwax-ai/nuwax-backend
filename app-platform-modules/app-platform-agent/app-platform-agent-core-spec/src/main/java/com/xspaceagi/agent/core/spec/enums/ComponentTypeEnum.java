package com.xspaceagi.agent.core.spec.enums;

public enum ComponentTypeEnum {
    Variable,
    Workflow,
    Plugin,
    Model,
    //知识库
    Knowledge,
    //数据库
    Database,
    Agent,
    Table,
    Mcp,
    Page,
    ToolCall,
    Plan,
    Event,
    SandboxStart,
    //总控智能体控制工具（returnDirect，执行直接返回入参JSON，不喂回模型）
    Control,
    //IM 原生工具：chatbot 直接调 IM RPC 收发消息/管会话，actor 身份由会话场景（群/私聊）解析，
    //不复用 ToolCall —— 那个是沙箱侧「工具调用」的展示类型，语义不同（见 ImChatToolExecutor）
    Im,
}