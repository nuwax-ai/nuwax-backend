package com.xspaceagi.custompage.domain.repository;

import java.util.List;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.SuperPage;

/**
 * 自定义页面会话记录仓储接口
 */
public interface ICustomPageConversationRepository {

    /**
     * 保存会话记录
     */
    Long save(CustomPageConversationModel model, UserContext userContext);

    /**
     * 根据项目ID查询会话记录列表
     */
    List<CustomPageConversationModel> listByProjectId(Long projectId, Long userId);

    /**
     * 分页查询会话记录
     */
    SuperPage<CustomPageConversationModel> pageQuery(CustomPageConversationModel queryModel, Long current,
            Long pageSize);

    /**
     * 根据requestId回填用户消息的sessionId
     */
    boolean updateUserSessionIdByRequestId(Long projectId, String requestId, String sessionId, Long userId);

    /**
     * 按 sessionId 查询最新一条用户消息
     */
    CustomPageConversationModel findLatestUserBySessionId(Long projectId, String sessionId);

    /**
     * 按项目查询最新一条用户消息（用于未传 request_id 且 USER 尚未回填 Agent sessionId 时的回退）
     */
    CustomPageConversationModel findLatestUserByProjectId(Long projectId);

    /**
     * 按 requestId 查询本轮助手消息
     */
    CustomPageConversationModel findAssistantByProjectIdAndRequestId(Long projectId, String requestId);

    CustomPageConversationModel findById(Long id);

    /**
     * 更新助手消息内容
     */
    boolean updateAssistantContent(Long id, String content, String requestId, UserContext userContext);

    /**
     * 按项目ID删除会话记录（软删）
     */
    boolean deleteByProjectId(Long projectId, Long userId);

}
