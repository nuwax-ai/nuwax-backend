package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import jakarta.annotation.Resource;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatMemoryApiServiceImpl implements ChatMemory {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Override
    public void add(@NotNull String conversationId, List<Message> messages) {
        messages.forEach(message -> conversationApplicationService.addRoundMessage(conversationId, message));
    }

    @Override
    public List<Message> get(@NotNull String conversationId) {
        return conversationApplicationService.getRoundMessages(conversationId, 12).stream().map(message -> (Message) message).collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
    }
}
