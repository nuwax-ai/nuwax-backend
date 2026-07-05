package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.messages.*;

import java.util.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageDto implements Message {

    private static final int MAX_TOKEN_WINDOW_SIZE = 64 * 1024;

    private Long index;

    private Long tenantId;

    @Schema(description = "消息发送方类型, User、Agent")
    private SenderType senderType;

    @Schema(description = "消息发送方ID")
    private String senderId;

    @Schema(description = "关联用户ID")
    private Long userId;

    @Schema(description = "关联的agentID")
    private Long agentId;

    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "assistant 模型回复；user 用户消息")
    private Role role;

    private MessageTypeEnum type;

    @Schema(description = "消息内容")
    private String text;

    @Schema(description = "消息时间")
    private Date time;

    @Schema(description = "消息附件")
    private List<AttachmentDto> attachments;

    @Schema(description = "思考内容")
    private String think;

    @Schema(description = "引用消息内容")
    private String quotedText;

    private boolean finished;

    private String finishReason;

    @Schema(description = "执行过程输出数据")
    private List<Object> componentExecutedList;

    @Override
    public Map<String, Object> getMetadata() {
        return null;
    }

    @Override
    public MessageType getMessageType() {
        if (role != null) {
            return MessageType.valueOf(role.name());
        }
        return MessageType.USER;
    }

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM,
        FUNCTION,
    }

    public enum SenderType {
        USER,
        AGENT,
        REMINDER
    }

    public static List<Message> toMessages(List<ChatMessageDto> messages) {
        List<Message> all = new ArrayList<>();
        //cachedMessageList转Message
        for (ChatMessageDto message : messages) {
            if (message.getRole().name().equals(MessageType.USER.name())) {
                String text = message.getText();
                if (text != null) {
                    text = text.replaceAll("<user-memory>[\\s\\S]*?</user-memory>", "").trim();
                }
                all.add(new UserMessage(text == null ? "" : text));
            }
            if (message.getRole().name().equals(MessageType.ASSISTANT.name())) {
                String text = removeSystemTagContent(message.getText());
                if (text != null) {
                    text = text.replaceAll("\n<div><markdown-custom-process[^>]*>.*?</markdown-custom-process></div>\n\n", "");
                    text = text.replaceAll("<markdown-custom-process[^>]*>.*?</markdown-custom-process>", "");
                }
                all.add(new AssistantMessage(text));
            }
            if (message.getRole().name().equals(MessageType.SYSTEM.name())) {
                all.add(new SystemMessage(message.getText()));
            }
        }

        Collections.reverse(all);
        Iterator<Message> iterator = all.iterator();
        int tokenCount = 0;
        MessageType messageType = MessageType.ASSISTANT;
        while (iterator.hasNext()) {
            Message next = iterator.next();
            if (messageType != next.getMessageType()) {
                iterator.remove();
                continue;
            }
            messageType = messageType == MessageType.USER ? MessageType.ASSISTANT : MessageType.USER;
            if (tokenCount >= MAX_TOKEN_WINDOW_SIZE) {
                iterator.remove();
            } else {
                tokenCount += TikTokensUtil.tikTokensCount(next.getText());
            }
        }
        Collections.reverse(all);
        removeIfFirstMessageIsAssistant(all);
        return all;
    }

    public static String removeSystemTagContent(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("<think>[\\s\\S]*?</think>", "").trim()
                .replaceAll("```xml[\\s\\S]*?<tool_.*>[\\s\\S]*?</tool_.*>[\\s\\S]*?```", " ")
                .replaceAll("<tool_.*>[\\s\\S]*?</tool_.*>", " ");
    }


    //移除第一条消息不是User的内容，避免像deepseek第一条消息不是User消息时，导致无法正常工具调用
    private static void removeIfFirstMessageIsAssistant(List<Message> all) {
        if (CollectionUtils.isEmpty(all)) {
            return;
        }
        if (all.get(0).getMessageType() == MessageType.ASSISTANT) {
            all.remove(0);
            removeIfFirstMessageIsAssistant(all);
        }
    }
}
