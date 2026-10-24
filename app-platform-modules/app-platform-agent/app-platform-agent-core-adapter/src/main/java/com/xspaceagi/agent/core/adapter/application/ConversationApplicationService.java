package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import org.springframework.ai.chat.messages.Message;

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

    Long createRepoDocConversation(Long userId, Long agentId, String slugId, String docName);

    /**
     * 创建会话，支持指定沙箱、目录与项目；目录仅当 sandboxId 为用户个人电脑（USER 类型沙箱）时生效；
     * projectId 传后关联项目并设为开发会话，projectType 与 projectId 配合定位项目（projectId 非空时必传）
     */
    ConversationDto createConversation(Long userId, Long agentId, boolean devMode, Map<String, Object> variables, Long sandboxId, String workspacePath, Long projectId, String projectType);

    void createConversationForPageApp(Long userId, Long agentId);

    ConversationDto createConversationForTaskCenter(Long tenantId, Long userId, Long agentId);

    /**
     * 创建项目开发会话；
     * sandboxId 为显式指定的沙箱（可空，-1=云端），
     * 空则回退为用户之前为此智能体选择的沙箱（Redis 粘性）；
     * workspacePath 为会话级自定义目录（仅 Agent/Plugin/Skill/PageApp 等开发类型生效：
     * 须锚定个人沙箱，云端/未指定报错；常规/全栈项目目录在项目创建时落绑定，此处忽略）
     */
    ConversationDto createConversationForProjectDevelopment(Long tenantId, Long userId, Long spaceId, Long devAgentId, String targetType, Long targetId, Long sandboxId, String workspacePath);

    /**
     * 创建任务会话，返回会话
     */
    ConversationDto createTaskConversation(Long userId, TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto);

    ConversationDto createConversationForMasterAgentLoop(Long tenantId, Long userId, Long agentId, String topic, Map<String, Object> variables);

    /**
     * 取消任务会话
     *
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
     */
    void deleteConversation(Long userId, Long id);

    /** 按调度任务 taskId 删除关联的 TASK 会话（uid 即任务 taskId），任务删除时联动清理 */
    void deleteTaskConversationByTaskId(String taskId);

    /** 按会话 id 直接删除（含消息、收藏及云端沙箱工作区清理，不做类型拦截）；供项目删除联动使用 */
    void deleteConversationDirect(Long conversationId);

    /**
     * 更新会话
     */
    void updateConversationTopic(Long userId, ConversationUpdateDto conversationUpdateDto);

    /**
     * 查询会话
     *
     */
    ConversationDto getConversation(Long userId, Long id);

    ConversationDto getConversationByCid(Long cid);

    /** 按开发目标反查会话列表（targetType 为 ProjectTypeEnum 的 name，一个项目可能有多个会话） */
    List<ConversationDto> getConversationListByDevTarget(String targetType, Long targetId);

    /** 按开发目标删除全部会话及其消息（项目彻底删除用）；返回删除的会话数 */
    int deleteByDevTarget(String targetType, Long targetId);

    /** 批量：同类型多个目标对象的全部会话 */
    List<ConversationDto> getConversationListByDevTargets(String targetType, List<Long> targetIds);

    /** 查询某用户在某开发目标下的最近一条会话（无匹配返回 null） */
    ConversationDto getLatestConversationByDevTarget(Long userId, String targetType, Long targetId);

    ConversationDto getConversationWithLastMessageByCid(Long cid);

    ConversationDto getConversationByUid(Long userId, String uid);

    ConversationDto getConversationByUid(String uid);


    List<ConversationDto> getConversationListByCIds(List<Long> cIds);

    /**
     * 查询会话列表
     *
     */
    List<ConversationDto> queryConversationList(Long userId, Long agentId);

    List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic);

    /**
     * 会话列表查询
     */
    List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
            ArchivedFilter archivedFilter);

    /**
     * 会话列表查询：collectedFilter=ONLY 时仅返回当前用户收藏的会话
     */
    List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
            ArchivedFilter archivedFilter, CollectedFilter collectedFilter);

    /**
     * 会话列表查询：projectFilter 过滤项目会话（常规项目/全栈应用开发会话），ALL 全部（默认）/ EXCLUDE 排除 / ONLY 仅项目会话
     */
    List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
            ArchivedFilter archivedFilter, CollectedFilter collectedFilter, ProjectConversationFilter projectFilter);

    /** 会话置顶/归档标记更新（会话归属校验） */
    ConversationDto updateConversationFlags(Long userId, Long conversationId, Boolean pinned, Boolean archived);

    /** 会话收藏（个人收藏，会话归属校验；列表查询时回填 collected 标记） */
    void collectConversation(Long userId, Long conversationId);

    /** 会话取消收藏 */
    void unCollectConversation(Long userId, Long conversationId);

    List<ConversationDto> queryLatestConversationList(Long userId, List<Long> agentIds, String topic, Integer limit);


    /**
     * 根据sandboxServerId查询会话列表
     *
     */
    List<ConversationDto> queryConversationListBySandboxServerId(Long sandboxServerId);

    /**
     * 查询任务会话列表
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

    List<AgentUserDto> queryAgentUserList(Long agentId, Long cursorUserId);

    Long nextConversationId(Long agentId, String sandboxServerId);

    List<Message> getRoundMessages(String conversationId, int i);

    void addRoundMessage(String conversationId, Message message);

    boolean clearConversationMessages(Long conversationId);
}
