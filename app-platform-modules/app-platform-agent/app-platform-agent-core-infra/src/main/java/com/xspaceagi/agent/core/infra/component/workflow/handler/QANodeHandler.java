package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.QaDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.QaNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecuteResult;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.enums.NodeExecuteStatus;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
import com.xspaceagi.agent.core.spec.enums.QaKeyEnum;
import com.xspaceagi.agent.core.spec.enums.SystemArgNameEnum;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.system.spec.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class QANodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        //如果没有会话id，是在做工作流测试，直接返回
        if (workflowContext.getAgentContext().getConversationId() == null) {
            return Mono.just(new HashMap<>());
        }
        QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) node.getNodeConfig();
        Map<String, Object> params = extraBindValueMap(workflowContext, node, qaNodeConfigDto.getInputArgs());
        String question = PlaceholderParser.resoleAndReplacePlaceholder(params, qaNodeConfigDto.getQuestion());
        if (StringUtils.isBlank(question)) {
            return Mono.just(new HashMap<>());
        }

        QaDto qaDto = getConversationQaInfo(workflowContext.getWorkflowContextServiceHolder().getRedisUtil(), workflowContext.getAgentContext().getConversationId());
        if (qaDto != null) {
            String answer = qaDto.getAnswer();
            Map<String, Object> output = new HashMap<>();
            output.put(SystemArgNameEnum.USER_RESPONSE.name(), answer);
            if (qaNodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.TEXT || qaNodeConfigDto.getAnswerType() == null) {
                if (CollectionUtils.isNotEmpty(qaNodeConfigDto.getOutputArgs()) && qaNodeConfigDto.getOutputArgs().size() >= 2) {

                    ModelContext modelContext = new ModelContext();
                    modelContext.setAgentContext(workflowContext.getCopiedAgentContext());
                    modelContext.setConversationId(workflowContext.getAgentContext().getConversationId());
                    modelContext.setModelConfig(qaNodeConfigDto.getModelConfig());
                    ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
                    modelCallConfigDto.setSystemPrompt("Extract field content based on the user's answer. When mandatory fields cannot be extracted, they can be left blank, but do not fabricate information");
                    modelCallConfigDto.setUserPrompt("question" + ":" + qaDto.getQuestion() + "\n" + "answer:" + answer + "\n");
                    modelCallConfigDto.setChatRound(0);
                    modelCallConfigDto.setStreamCall(true);
                    modelCallConfigDto.setOutputType(OutputTypeEnum.JSON);
                    modelCallConfigDto.setOutputArgs(qaNodeConfigDto.getOutputArgs());
                    modelCallConfigDto.setMaxTokens(qaNodeConfigDto.getMaxTokens());
                    modelCallConfigDto.setTemperature(qaNodeConfigDto.getTemperature());
                    modelCallConfigDto.setTopP(qaNodeConfigDto.getTopP());
                    modelContext.setModelCallConfig(modelCallConfigDto);

                    ModelInvoker modelInvoker = workflowContext.getWorkflowContextServiceHolder().getModelInvoker();
                    return modelInvoker.invoke(modelContext).last().map(result -> {
                        log.info("User response extraction result:{}", modelContext.getModelCallResult().getData());
                        Object data = modelContext.getModelCallResult().getData();
                        if (data != null) {
                            Map<String, Object> res = (Map<String, Object>) data;
                            res.putAll(output);
                            //校验data中的数据是否符合output定义
                            StringBuilder sb = new StringBuilder("Please provide additional information to obtain the following details\n");
                            AtomicBoolean flag = new AtomicBoolean(false);
                            qaNodeConfigDto.getOutputArgs().forEach(arg -> {
                                if (arg.isRequire() && (res.get(arg.getName()) == null || "".equals(res.get(arg.getName())))) {
                                    flag.set(true);
                                    sb.append(arg.getDescription()).append("\n");
                                }
                            });
                            //没有获取到信息，继续提问
                            if (flag.get() && qaDto.getAskTimes() < qaNodeConfigDto.getMaxReplyCount()) {
                                buildAndSendOutput(workflowContext, node, question, sb.toString(), null, qaDto);
                                return new HashMap<>();
                            }
                        }
                        clearConversationQaInfo(workflowContext.getWorkflowContextServiceHolder().getRedisUtil(), workflowContext.getAgentContext().getConversationId());
                        return data == null ? new HashMap<>() : data;
                    });
                } else {
                    clearConversationQaInfo(workflowContext.getWorkflowContextServiceHolder().getRedisUtil(), workflowContext.getAgentContext().getConversationId());
                    return Mono.just(output);
                }
            } else {
                if (CollectionUtils.isEmpty(qaNodeConfigDto.getOptions())) {
                    return Mono.just(new HashMap<>());
                }
                setUnreachableNextNodeIds(node, answer);
                clearConversationQaInfo(workflowContext.getWorkflowContextServiceHolder().getRedisUtil(), workflowContext.getAgentContext().getConversationId());
                return Mono.just(output);
            }
        }

        List<QaNodeConfigDto.OptionConfigDto> options = null;
        if (qaNodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT && CollectionUtils.isNotEmpty(qaNodeConfigDto.getOptions())) {
            qaNodeConfigDto.getOptions().forEach(optionConfigDto -> optionConfigDto.setNextNodeIds(null));
            options = qaNodeConfigDto.getOptions();
        }
        buildAndSendOutput(workflowContext, node, question, null, options, null);
        return Mono.just(new HashMap<>());
    }

    private void setUnreachableNextNodeIds(WorkflowNodeDto node, String answer) {
        QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) node.getNodeConfig();
        Set<Long> reachableNextNodeIds = new HashSet<>();
        Set<Long> unReachableNextNodeIds = new HashSet<>();
        qaNodeConfigDto.getOptions().forEach(optionConfigDto -> {
            if (!optionConfigDto.getIndex().equals(answer) && !optionConfigDto.getContent().equals(answer)) {
                //设置无法执行的下级节点
                unReachableNextNodeIds.addAll(optionConfigDto.getNextNodeIds());
            } else {
                //设置可以执行的下级节点
                reachableNextNodeIds.addAll(optionConfigDto.getNextNodeIds());
            }
        });
        unReachableNextNodeIds.removeAll(reachableNextNodeIds);
        node.setUnreachableNextNodeIds(unReachableNextNodeIds);
        if (reachableNextNodeIds.isEmpty()) {
            //最后一个节点作为默认节点
            qaNodeConfigDto.getOptions().get(qaNodeConfigDto.getOptions().size() - 1).getNextNodeIds().forEach(node.getUnreachableNextNodeIds()::remove);
        }
    }

    private void buildAndSendOutput(WorkflowContext workflowContext, WorkflowNodeDto node, String question, String extraQuestion, List<QaNodeConfigDto.OptionConfigDto> options, QaDto qaDtoCached) {
        if (options != null) {
            options.removeIf(optionConfigDto -> optionConfigDto.getContent() == null);
            if (!options.isEmpty()) {
                options.remove(options.size() - 1);
            }
        }
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        agentOutputDto.setRequestId(workflowContext.getRequestId());
        CallMessage callMessage = new CallMessage();
        callMessage.setId(workflowContext.getRequestId());
        callMessage.setFinished(true);
        callMessage.setText(extraQuestion == null ? question : extraQuestion);
        callMessage.setType(MessageTypeEnum.QUESTION);
        callMessage.setExt(options);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        workflowContext.getAgentContext().getAgentExecuteResult().setOutputText(callMessage.getText());
        agentOutputDto.setData(callMessage);
        agentOutputDto.setCompleted(false);
        if (workflowContext.getAgentContext().getOutputConsumer() != null || workflowContext.getNodeExecutingConsumer() != null) {
            if (qaDtoCached != null) {
                qaDtoCached.setAnswer(qaDtoCached.getAnswer());
                qaDtoCached.setQuestion(qaDtoCached.getQuestion() + "\n" + (extraQuestion == null ? "" : extraQuestion));
                qaDtoCached.setAskTimes(qaDtoCached.getAskTimes() + 1);
            } else {
                qaDtoCached = new QaDto();
                qaDtoCached.setAnswer("");
                qaDtoCached.setQuestion(extraQuestion == null ? question : extraQuestion);
                qaDtoCached.setAskTimes(1);
                qaDtoCached.setNodeId(node.getId());
                qaDtoCached.setWorkflowId(workflowContext.getOriginalWorkflowId());
                qaDtoCached.setRequestId(workflowContext.getRequestId());
                qaDtoCached.setOriginMessage(workflowContext.getAgentContext().getMessage());
            }
            addOrUpdateConversationQaInfo(workflowContext.getWorkflowContextServiceHolder().getRedisUtil(), workflowContext.getAgentContext().getConversationId(), qaDtoCached);
            workflowContext.getAgentContext().setInterrupted(true);
            if (workflowContext.getAgentContext().getOutputConsumer() != null) {
                workflowContext.getAgentContext().getOutputConsumer().accept(agentOutputDto);
            } else {
                //工作流测试
                NodeExecutingDto nodeExecutingDto = new NodeExecutingDto();
                nodeExecutingDto.setStatus(NodeExecuteStatus.STOP_WAIT_ANSWER);
                nodeExecutingDto.setNodeId(node.getId());
                nodeExecutingDto.setResult(new NodeExecuteResult());
                nodeExecutingDto.getResult().setStartTime(System.currentTimeMillis());
                nodeExecutingDto.getResult().setEndTime(System.currentTimeMillis());
                nodeExecutingDto.getResult().setData(Map.of("question", extraQuestion == null ? question : extraQuestion, "options", options == null ? new ArrayList<>() : options));
                workflowContext.getNodeExecutingConsumer().accept(nodeExecutingDto);
            }
            if (workflowContext.isUseResultCache()) {
                //开启使用缓存结果都是在工作流被自动调用的情况下
                addConversationMessage(workflowContext, workflowContext.getAgentContext(), workflowContext.getAgentContext().getAgentConfig(), workflowContext.getAgentContext().getConversationId(), new UserMessage(workflowContext.getAgentContext().getMessage()));
            }
            addConversationMessage(workflowContext, workflowContext.getAgentContext(), workflowContext.getAgentContext().getAgentConfig(), workflowContext.getAgentContext().getConversationId(), callMessage);
        }
    }

    public void addConversationMessage(WorkflowContext workflowContext, AgentContext agentContext, AgentConfigDto agentConfig, String conversationId, Message message) {
        //保存的会话信息
        if (agentConfig != null) {
            if (message instanceof ChatMessageDto) {
                ((ChatMessageDto) message).setTenantId(agentConfig.getTenantId());
                ((ChatMessageDto) message).setId(UUID.randomUUID().toString().replace("-", ""));
                ((ChatMessageDto) message).setSenderId(agentConfig.getId().toString());
                ((ChatMessageDto) message).setSenderType(ChatMessageDto.SenderType.AGENT);
                ((ChatMessageDto) message).setUserId(agentContext.getUserId());
                ((ChatMessageDto) message).setAgentId(agentConfig.getId());
                ((ChatMessageDto) message).setTime(new Date());
                workflowContext.getWorkflowContextServiceHolder().getChatMemory().add(conversationId, message);
                return;
            }
            ChatMessageDto chatMessage = ChatMessageDto.builder()
                    .tenantId(agentConfig.getTenantId())
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .senderId(agentConfig.getId().toString())
                    .senderType(ChatMessageDto.SenderType.AGENT)
                    .userId(agentContext.getUserId())
                    .agentId(agentConfig.getId())
                    .text(message.getText())
                    .time(new Date())
                    .type(MessageTypeEnum.CHAT)
                    .role(ChatMessageDto.Role.ASSISTANT)
                    .build();
            workflowContext.getWorkflowContextServiceHolder().getChatMemory().add(conversationId, chatMessage);
        }
    }

    public void addOrUpdateConversationQaInfo(RedisUtil redisUtil, String conversationId, QaDto qaDto) {
        Map<String, Object> hash = new HashMap<>();
        hash.put(QaKeyEnum.ASK_TIMES.name(), qaDto.getAskTimes());
        hash.put(QaKeyEnum.ORIGIN_MESSAGE.name(), qaDto.getOriginMessage());
        hash.put(QaKeyEnum.QUESTION.name(), qaDto.getQuestion());
        hash.put(QaKeyEnum.ANSWER.name(), qaDto.getAnswer());
        hash.put(QaKeyEnum.REQUEST_ID.name(), qaDto.getRequestId());
        hash.put(QaKeyEnum.NODE_ID.name(), qaDto.getNodeId());
        hash.put(QaKeyEnum.WORKFLOW_ID.name(), qaDto.getWorkflowId());
        redisUtil.hashPutAll("qa:" + conversationId, hash);
        redisUtil.expire("qa:" + conversationId, 86400);
    }

    public QaDto getConversationQaInfo(RedisUtil redisUtil, String conversationId) {
        Map<String, Object> hash = redisUtil.hashGetAll("qa:" + conversationId);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        QaDto qaDto = new QaDto();
        if (hash.get(QaKeyEnum.ASK_TIMES.name()) != null) {
            qaDto.setAskTimes(Integer.parseInt(hash.get(QaKeyEnum.ASK_TIMES.name()).toString()));
        }
        if (hash.get(QaKeyEnum.ORIGIN_MESSAGE.name()) != null) {
            qaDto.setOriginMessage(hash.get(QaKeyEnum.ORIGIN_MESSAGE.name()).toString());
        }
        if (hash.get(QaKeyEnum.QUESTION.name()) != null) {
            qaDto.setQuestion(hash.get(QaKeyEnum.QUESTION.name()).toString());
        }
        if (hash.get(QaKeyEnum.ANSWER.name()) != null) {
            qaDto.setAnswer(hash.get(QaKeyEnum.ANSWER.name()).toString());
        }
        if (hash.get(QaKeyEnum.REQUEST_ID.name()) != null) {
            qaDto.setRequestId(hash.get(QaKeyEnum.REQUEST_ID.name()).toString());
        }
        if (hash.get(QaKeyEnum.NODE_ID.name()) != null) {
            qaDto.setNodeId(Long.parseLong(hash.get(QaKeyEnum.NODE_ID.name()).toString()));
        }
        if (hash.get(QaKeyEnum.WORKFLOW_ID.name()) != null) {
            qaDto.setWorkflowId(Long.parseLong(hash.get(QaKeyEnum.WORKFLOW_ID.name()).toString()));
        }
        return qaDto;
    }

    private void clearConversationQaInfo(RedisUtil redisUtil, String conversationId) {
        redisUtil.expire("qa:" + conversationId, -1);
    }
}
