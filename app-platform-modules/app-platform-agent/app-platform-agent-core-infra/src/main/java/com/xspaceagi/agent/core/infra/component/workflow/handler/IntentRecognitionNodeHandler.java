package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.IntentRecognitionNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class IntentRecognitionNodeHandler extends AbstractNodeHandler {

    private static final String INTENT_RECOGNITION_SYSTEM_PROMPT = """
            Please identify the intent based on the information provided below, and match it with preset intent options. If no match is found, select "Other Intent".
            """;

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = (IntentRecognitionNodeConfigDto) node.getNodeConfig();
        Map<String, Object> params = extraBindValueMap(workflowContext, node, intentRecognitionNodeConfigDto.getInputArgs());
        ModelContext modelContext = new ModelContext();
        modelContext.setAgentContext(workflowContext.getCopiedAgentContext());
        modelContext.setConversationId(workflowContext.getAgentContext().getConversationId());
        modelContext.setModelConfig(intentRecognitionNodeConfigDto.getModelConfig());
        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        AtomicInteger classificationIndex = new AtomicInteger(0);
        List<Map<String, Object>> intentOptions = intentRecognitionNodeConfigDto.getIntentConfigs().stream().collect(ArrayList::new, (list, intentConfigDto) -> {
            Map<String, Object> intentOption = new HashMap<>();
            intentOption.put("classificationId", classificationIndex.getAndIncrement());
            intentOption.put("intent", intentConfigDto.getIntent());
            list.add(intentOption);
        }, ArrayList::addAll);
        StringBuilder stringBuilder = new StringBuilder(INTENT_RECOGNITION_SYSTEM_PROMPT).append("\n");
        if (StringUtils.isNotBlank(intentRecognitionNodeConfigDto.getExtraPrompt())) {
            stringBuilder.append(intentRecognitionNodeConfigDto.getExtraPrompt()).append("\n");
        }
        if (intentRecognitionNodeConfigDto.getUseHistory() != null && intentRecognitionNodeConfigDto.getUseHistory()) {
            ChatMemory chatMemory = workflowContext.getWorkflowContextServiceHolder().getChatMemory();
            List<Message> messages = chatMemory.get(workflowContext.getAgentContext().getConversationId(), 12);
            if (CollectionUtils.isNotEmpty(messages)) {
                stringBuilder.append("Conversation history:\n");
                messages.forEach(message -> stringBuilder.append(message.getMessageType().name()).append(":").append(message.getText()).append("\n"));
            }
        }
        stringBuilder.append("Recognition parameters:\n").append(JSON.toJSONString(params)).append("\n");
        stringBuilder.append("Intent options:\n").append(JSON.toJSONString(intentOptions));
        modelCallConfigDto.setSystemPrompt(stringBuilder.toString());
        modelCallConfigDto.setUserPrompt("");
        modelCallConfigDto.setChatRound(0);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setOutputType(OutputTypeEnum.JSON);

        // Build default output parameters for intent recognition
        var outputArgs = intentRecognitionNodeConfigDto.getOutputArgs();

        modelCallConfigDto.setOutputArgs(outputArgs);
        modelCallConfigDto.setMaxTokens(intentRecognitionNodeConfigDto.getMaxTokens());
        modelCallConfigDto.setTemperature(intentRecognitionNodeConfigDto.getTemperature());
        modelCallConfigDto.setTopP(intentRecognitionNodeConfigDto.getTopP());
        modelContext.setModelCallConfig(modelCallConfigDto);
        ModelInvoker modelInvoker = workflowContext.getWorkflowContextServiceHolder().getModelInvoker();
        return modelInvoker.invoke(modelContext).last().map(result -> {
            Set<Long> reachableNextNodeIds = new HashSet<>();
            Set<Long> unReachableNextNodeIds = new HashSet<>();
            Object data = modelContext.getModelCallResult().getData();
            int classificationId = classificationIndex.get();
            Object reason = "Intent not recognized, select Other Intent";
            if ((data instanceof Map<?, ?>)) {
                Map<String, Object> output = (Map<String, Object>) data;
                Object classificationId0 = output.get("classificationId");
                if (classificationId0 != null) {
                    try {
                        classificationId = classificationId0 instanceof Number ? ((Number) classificationId0).intValue() : Integer.parseInt(classificationId0.toString());
                        reason = output.get("reason");
                    } catch (NumberFormatException e) {
                        classificationId = classificationIndex.get();
                    }
                }
            }

            final Integer classificationId0 = classificationId;
            // Filter out options with the same classificationId from intentOptions
            Map<String, Object> optional = intentOptions.stream().filter(intentOption -> classificationId0.equals(intentOption.get("classificationId"))).findFirst().orElse(new HashMap<>());
            if (optional.isEmpty()) {
                optional.put("classificationId", classificationIndex.get());
                optional.put("intent", "Other Intent");
                reason = "Intent not recognized, select Other Intent";
            }
            intentRecognitionNodeConfigDto.getIntentConfigs().forEach(intentConfigDto -> {
                if (intentConfigDto.getIntent().equals(optional.get("intent"))) {
                    reachableNextNodeIds.addAll(intentConfigDto.getNextNodeIds());
                } else {
                    unReachableNextNodeIds.addAll(intentConfigDto.getNextNodeIds());
                }
            });
            unReachableNextNodeIds.removeAll(reachableNextNodeIds);
            node.setUnreachableNextNodeIds(unReachableNextNodeIds);
            return Map.of("classificationId", classificationId, "reason", reason);
        });
    }
}
