package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface ConversationApplicationService {

    /**
     * 创建会话，返回会话
     */
    ConversationDto createConversation(Long userId, Long agentId, boolean devMode);

    ConversationDto createConversation(Long userId, Long agentId, boolean devMode, Map<String, Object> variables);

    ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables);

    ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat);

    void createConversationForPageApp(Long userId, Long agentId);

    ConversationDto createConversationForTaskCenter(Long tenantId, Long userId, Long agentId);

    ConversationDto createConversationForProjectDevelopment(Long tenantId, Long userId, Long spaceId, Long devAgentId, String targetType, Long targetId);

    /**
     * 创建任务会话，返回会话
     *
     * @param userId
     * @return
     */
    ConversationDto createTaskConversation(Long userId, TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto);


    /**
     * 取消任务会话
     *
     * @param conversationId
     */
    void cancelTaskConversation(Long userId, Long conversationId);

    /**
     * 更新任务会话
     */
    void updateTaskConversation(Long userId, TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto);

    void updateConversationSandboxServerId(Long cid, String sandboxServerId);


    void updateConversationStatus(Long cid, Conversation.ConversationTaskStatus status);

    void updateConversationVariables(Long id, Map<String, Object> variables);

    /**
     * 删除会话
     *
     * @param userId
     * @param id
     */
    void deleteConversation(Long userId, Long id);

    /**
     * 更新会话
     */
    void updateConversationTopic(Long userId, ConversationUpdateDto conversationUpdateDto);

    /**
     * 查询会话
     *
     * @param userId
     * @param id
     * @return
     */
    ConversationDto getConversation(Long userId, Long id);

    ConversationDto getConversationByCid(Long cid);

    ConversationDto getConversationByUid(Long userId, String uid);

    ConversationDto getConversationByUid(String uid);


    /**
     * 查询会话列表
     *
     * @param userId
     * @return
     */
    List<ConversationDto> queryConversationList(Long userId, Long agentId);

    List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic);


    /**
     * 根据sandboxServerId查询会话列表
     *
     * @return
     */
    List<ConversationDto> queryConversationListBySandboxServerId(Long sandboxServerId);

    /**
     * 查询任务会话列表
     *
     * @param userId
     * @return
     */
    List<ConversationDto> queryTaskConversationList(Long userId, Long agentId, Conversation.ConversationTaskStatus taskStatus);

    /**
     * 查询会话消息
     */
    List<ChatMessageDto> queryConversationMessageList(Long userId, Long conversationId, Long index, int size);

    /**
     * 总结会话
     */
    void summaryConversation(Long conversationId);

    /**
     * 放进会话总结队列
     *
     * @param conversationId 会话id
     */
    void pushToSummaryQueue(Long conversationId);

    String queryMemory(Long tenantId, Long userId, Long agentId, String inputMessage, String context, boolean justKeywordSearch, boolean filterSensitive);

    Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat);

    Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat, Boolean devMode);

    List<AgentUserDto> queryAgentUserList(Long agentId, Long cursorUserId);

    Long nextConversationId(Long agentId, String sandboxServerId);

    List<Message> getRoundMessages(String conversationId, int i);

    List<ChatMessageDto> getRoundMessages(String conversationId, Long minId);

    void addRoundMessage(String conversationId, Message message);

    void setChatStopStatus(String conversationId);

    boolean isChatStop(String conversationId);

    void clearChatStopStatus(String conversationId);
}
