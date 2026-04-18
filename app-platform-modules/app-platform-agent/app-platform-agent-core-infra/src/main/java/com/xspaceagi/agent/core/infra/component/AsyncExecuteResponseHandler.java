package com.xspaceagi.agent.core.infra.component;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.EndNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.system.application.dto.EventDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.spec.utils.I18nUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class AsyncExecuteResponseHandler {

    private NotifyMessageApplicationService notifyMessageApplicationService;

    // Current context memory
    private ChatMemory chatMemory;

    private ModelInvoker modelInvoker;

    @Autowired
    public void setNotifyMessageApplicationService(NotifyMessageApplicationService notifyMessageApplicationService) {
        this.notifyMessageApplicationService = notifyMessageApplicationService;
    }

    @Autowired
    public void setChatMemory(ConversationApplicationService conversationApplicationService) {
        this.chatMemory = (ChatMemory) conversationApplicationService;
    }

    @Autowired
    public void setModelInvoker(ModelInvoker modelInvoker) {
        this.modelInvoker = modelInvoker;
    }

    public void handleWorkflowSuccess(WorkflowContext workflowContext, Object result) {
        AgentContext agentContext = workflowContext.getCopiedAgentContext();
        if (agentContext == null) {
            return;
        }
        WorkflowConfigDto workflowConfigDto = workflowContext.getWorkflowConfig();
        EndNodeConfigDto endNodeConfigDto = (EndNodeConfigDto) workflowConfigDto.getEndNode().getNodeConfig();
        if (endNodeConfigDto.getReturnType() == EndNodeConfigDto.ReturnType.TEXT && StringUtils.isNotBlank(workflowContext.getEndNodeContent())) {
            addMessage(agentContext, workflowContext.getEndNodeContent());
        } else {
            buildAndAddMessage(agentContext, result);
        }
    }

    private void buildAndAddMessage(AgentContext agentContext, Object result) {
        String userPrompt = "<tool_call_result>" + PlaceholderParser.parseString(result) + "</tool_call_result>\n" +
                "<user_message>" + agentContext.getMessage() + "</user_message>\n";
        String systemPrompt = """
                # Role:
                 - Information Integration and Output Optimization Expert
                
                ## Background:
                - Users need to convert tool execution results into highly readable answers while avoiding outputting raw tags and redundant information
                
                ## Attention:
                - Maintain the professionalism and accuracy of the answer while improving readability and user experience
                
                ## Profile:
                - Language: Based on content itself
                - Description: Specialized in converting technical tool results into user-friendly natural language expressions
                
                ### Skills:
                - Information extraction and integration
                - Natural language expression
                - Key information identification
                - Technical terminology conversion
                - User experience optimization
                
                ## Goals:
                - Accurately understand tool execution results
                - Identify the core needs of user questions
                - Generate concise and clear answers
                - Preserve all key information
                - Improve answer readability
                
                ## Constraints:
                - Do not output <tool_call_result> and <user_message> tag information
                - Do not lose key information
                - Do not fabricate unprovided information
                - Maintain professionalism of the answer
                - Use user-friendly language
                
                ## Workflow:
                1. Parse tool execution results and extract key data
                2. Analyze user questions and determine answer direction
                3. Convert technical results into natural language
                4. Organize answer structure to ensure clear logic
                5. Check if all key information is preserved
                6. Optimize language expression to improve readability
                7. Output the final answer
                
                ## OutputFormat:
                - Use complete natural language sentences
                - Maintain clear paragraph structure
                - Use appropriate transition words
                - Avoid outputting <tool_call_result> and <user_message> tags
                - If there are image links, display them directly via markdown, for example: ![Image Description](https://example.com/image.jpg)
                
                ## Suggestions:
                - Fully understand all details of tool results before conversion
                - Identify implicit needs in user questions
                - Use metaphors or examples to explain complex concepts
                - Use emphasis formatting for important data
                - Keep answer length appropriate, neither too brief nor too verbose
                
                ## Initialization
                As an Information Integration and Output Optimization Expert, you must follow all constraints, communicate with users in language appropriate to their query, and ensure the output is both professional and easy to understand.
                """;
        // LLM rewrite output
        ModelContext modelContext = buildModelContext(agentContext, systemPrompt, userPrompt);
        modelInvoker.invoke(modelContext).doOnComplete(() -> {
            String responseText = modelContext.getModelCallResult().getResponseText();
            addMessage(agentContext, responseText);
        }).doOnError(throwable -> addMessage(agentContext, JSON.toJSONString(result))).subscribe();
    }

    private void addMessage(AgentContext agentContext, String responseText) {
        ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .role(ChatMessageDto.Role.ASSISTANT)
                .text(responseText)
                .type(MessageTypeEnum.CHAT)
                .quotedText(agentContext.getMessage())
                .build();
        chatMemory.add(agentContext.getConversationId(), chatMessageDto);
        notifyMessage(agentContext, chatMessageDto);
    }

    private void notifyMessage(AgentContext agentContext, ChatMessageDto chatMessageDto) {
        chatMessageDto.setTenantId(agentContext.getAgentConfig().getTenantId());
        chatMessageDto.setSenderId(String.valueOf(agentContext.getAgentConfig().getId()));
        chatMessageDto.setSenderType(ChatMessageDto.SenderType.AGENT);
        chatMessageDto.setAgentId(agentContext.getAgentConfig().getId());
        chatMessageDto.setUserId(agentContext.getUserId());
        EventDto event = EventDto.builder()
                .type(EventDto.EVENT_TYPE_REFRESH_CHAT_MESSAGE)
                .event(Map.of("conversationId", agentContext.getConversationId(), "message", chatMessageDto))
                .build();
        notifyMessageApplicationService.publishEvent(agentContext.getUserId(), event);
    }

    public void handlePluginSuccess(PluginContext pluginContext, Object result) {
        buildAndAddMessage(pluginContext.getAgentContext(), result);
    }

    public void handleError(AgentContext agentContext, ComponentTypeEnum type, Throwable throwable) {
        String componentType = type == ComponentTypeEnum.Workflow ?
                I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Agent.AsyncExecute.Error.WorkflowType") :
                I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Agent.AsyncExecute.Error.PluginType");
        String errorMessage = componentType +
                I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Agent.AsyncExecute.Error.AsyncExecutionFailed",
                        throwable.getMessage());
        addMessage(agentContext, errorMessage);
    }

    private static ModelContext buildModelContext(AgentContext agentContext, String systemPrompt, String userPrompt) {
        ModelContext modelContext = new ModelContext();
        modelContext.setRequestId(agentContext.getRequestId());
        modelContext.setAgentContext(agentContext);
        modelContext.setModelConfig((ModelConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig());
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        modelCallConfigDto.setSystemPrompt(systemPrompt);
        modelCallConfigDto.setUserPrompt(userPrompt);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setMaxTokens(modelBindConfigDto.getMaxTokens());
        modelCallConfigDto.setTemperature(modelBindConfigDto.getTemperature());
        modelCallConfigDto.setTopP(modelBindConfigDto.getTopP());
        modelContext.setModelCallConfig(modelCallConfigDto);
        return modelContext;
    }
}
