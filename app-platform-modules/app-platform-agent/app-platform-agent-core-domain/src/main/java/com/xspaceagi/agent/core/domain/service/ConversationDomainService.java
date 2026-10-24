package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.dto.ArchivedFilter;
import com.xspaceagi.agent.core.adapter.dto.ProjectConversationFilter;
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

    /** 按开发目标反查会话列表（dev_target_type + dev_target_id，一个项目可能有多个会话，租户隔离） */
    List<Conversation> getConversationListByDevTarget(String devTargetType, Long devTargetId);

    /** 批量：同类型多个目标对象的全部会话（devTargetId 为 varchar，按字符串比较避免隐式转换） */
    List<Conversation> getConversationListByDevTargets(String devTargetType, List<Long> devTargetIds);

    Conversation getLatestConversationByDevTarget(Long userId, String devTargetType, Long devTargetId);

    List<Conversation> getConversationListByIds(List<Long> ids);

    /**
     * 查询会话列表
     */
    List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic);

    /** archivedFilter：ALL 全部（默认）/ EXCLUDE 排除归档 / ONLY 仅归档 */
    List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic, ArchivedFilter archivedFilter);

    /** collectedIds：null 不过滤收藏；非空 = 仅查询这些会话 */
    List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic, ArchivedFilter archivedFilter, List<Long> collectedIds);

    /** projectFilter：项目会话过滤（常规项目/全栈应用开发会话），ALL 全部（默认）/ EXCLUDE 排除 / ONLY 仅项目会话 */
    List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic, ArchivedFilter archivedFilter, List<Long> collectedIds, ProjectConversationFilter projectFilter);

    List<Conversation> queryLatestConversationList(Long userId, List<Long> agentIds, String topic, Integer limit);

    List<Conversation> queryTaskConversationList(Long userId, Long agentId, Conversation.ConversationTaskStatus status, int size);

    List<Conversation> queryConversationListBySandboxServerId(Long sandboxServerId);

    long agentUserCount(Long userId, Long agentId);

    List<Long> queryAgentUserIdList(Long agentId, Long cursorUserId);

    Long maxConversationId();

    Long nextConversationId(Long userId, Long agentId, String sandboxServerId);

    Long addConversationMessage(ConversationMessage conversationMessage);

    ConversationMessage getConversationMessage(String messageId);

    boolean deleteConversationMessage(String messageId);

    boolean clearConversationMessages(Long conversationId);

    List<ConversationMessage> queryConversationMessageList(Long conversationId, Long lastId, int size);

    List<Conversation> queryLatestSandboxConversationList(List<Long> exceptSandboxIds);
}
