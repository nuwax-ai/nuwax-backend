package com.xspaceagi.agent.core.infra.component.agent;

import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class AgentContext implements Serializable {

    @Schema(description = "用户ID")
    private Long userId;

    private String uid;

    private String userName;

    @Schema(description = "用户", hidden = true)
    private UserDto user;

    @Schema(description = "租户配置", hidden = true)
    private TenantConfigDto tenantConfig;

    @Schema(description = "请求追踪ID")
    private String requestId;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "用户消息")
    private String message;

    @Schema(description = "用户发送的原始聊天消息")
    private String originalMessage;

    @Schema(description = "附件列表")
    private List<AttachmentDto> attachments;

    @Schema(description = "执行结果")
    private AgentExecuteResult agentExecuteResult;

    @Schema(description = "变量参数列表")
    private Map<String, Object> variableParams;

    @Schema(description = "智能体配置")
    private AgentConfigDto agentConfig;

    @Schema(description = "会话信息")
    private ConversationDto conversation;

    @Schema(description = "请求头")
    private Map<String, String> headers;

    @Schema(description = "长期记忆")
    private String longMemory;

    @Schema(description = "对话上下文")
    private List<Message> contextMessages;

    @Schema(description = "自动工具调用消息")
    private List<Message> autoToolCallMessages;

    @Schema(description = "调用轨迹")
    private TraceContext traceContext;

    @Schema(description = "过程输出")
    private Consumer<AgentOutputDto> outputConsumer;

    @Schema(description = "组件执行跟踪")
    private Consumer<ComponentExecutingDto> componentExecutingConsumer;

    private UserDataPermissionDto userDataPermission;

    @Schema(description = "是否过滤长期记忆中的敏感信息")
    private boolean filterSensitive;

    private boolean debug;
    private boolean defaultModelChanged;
    private Function<Long, AgentContext> agentContextFunction;

    private Consumer<ConversationDto> sandboxSessionCreatedConsumer;

    // 是否已经中断
    private AtomicBoolean ifInterrupted = new AtomicBoolean(false);

    private AtomicBoolean finished = new AtomicBoolean(false);

    public Map<String, Object> getVariableParams() {
        if (variableParams == null) {
            variableParams = new HashMap<>();
            //系统变量
            if (agentConfig != null) {
                variableParams.put(GlobalVariableEnum.AGENT_ID.name(), agentConfig.getId());
            } else {
                variableParams.put(GlobalVariableEnum.AGENT_ID.name(), -1L);
            }
            variableParams.put(GlobalVariableEnum.AGENT_USER_MSG.name(), getMessage());
            variableParams.put(GlobalVariableEnum.REQUEST_ID.name(), getRequestId());
            variableParams.put(GlobalVariableEnum.CONVERSATION_ID.name(), getConversationId());
            variableParams.put(GlobalVariableEnum.SYS_USER_ID.name(), getUserId());
            variableParams.put(GlobalVariableEnum.USER_UID.name(), getUid());
            variableParams.put(GlobalVariableEnum.USER_NAME.name(), getUserName());
            if (user != null) {
                variableParams.put(GlobalVariableEnum.SYS_USER_ID.name(), user.getId());
                variableParams.put(GlobalVariableEnum.USER_UID.name(), user.getUid());
                variableParams.put(GlobalVariableEnum.USER_NAME.name(), user.getNickName() == null ? user.getUserName() : user.getNickName());
                variableParams.put(GlobalVariableEnum.USER_LANG.name(), user.getLang());
            }
        }
        return variableParams;
    }

    public Map<String, String> getHeaders() {
        return headers == null ? headers = new HashMap<>() : headers;
    }

    public AgentExecuteResult getAgentExecuteResult() {
        return agentExecuteResult == null ? agentExecuteResult = new AgentExecuteResult() : agentExecuteResult;
    }

    public void setInterrupted(boolean interrupted) {
        if (this.ifInterrupted == null) {
            this.ifInterrupted = new AtomicBoolean(interrupted);
        } else {
            this.ifInterrupted.set(interrupted);
        }
    }

    public boolean isInterrupted() {
        return ifInterrupted != null && ifInterrupted.get();
    }
}
