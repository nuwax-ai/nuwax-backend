package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.ConversationMessage;

import java.util.List;

public interface ConversationDomainService {

    /**
     * 创建会话
     *
     * @param conversation
     */
    void createConversation(Conversation conversation);

    /**
     * 删除会话
     */
    void deleteConversation(Long userId, Long id);

    /**
     * 更新会话
     *
     * @param conversation
     */
    boolean updateConversation(Long userId, Long id, Conversation conversation);


    boolean updateConversation(Long id, Conversation conversation);

    /**
     * 获取会话
     *
     * @param id
     * @return
     */
    Conversation getConversation(Long userId, Long id);

    Conversation getConversationByUid(Long userId, String uid);

    Conversation getConversationByUid(String uid);

    Conversation getConversation(Long id);

    /**
     * 查询会话列表
     */
    List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic);

    List<Conversation> queryTaskConversationList(Long userId, Long agentId, Conversation.ConversationTaskStatus status, int size);

    List<Conversation> queryConversationListBySandboxServerId(Long sandboxServerId);

    long agentUserCount(Long userId, Long agentId);

    List<Long> queryAgentUserIdList(Long agentId, Long cursorUserId);

    Long maxConversationId();

    Long nextConversationId(Long userId, Long agentId, String sandboxServerId);

    Long addConversationMessage(ConversationMessage conversationMessage);

    ConversationMessage getConversationMessage(String messageId);

    boolean deleteConversationMessage(String messageId);

    List<ConversationMessage> queryConversationMessageList(Long conversationId, Long lastId, int size);

    List<ConversationMessage> queryConversationMessageList(Long conversationId, Long minId);

    List<Conversation> queryLatestSandboxConversationList(List<Long> exceptSandboxIds);
}
