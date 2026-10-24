package com.xspaceagi.custompage.domain.service;

import java.util.List;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.SuperPage;

/**
 * 用户页面会话记录领域服务接口
 */
public interface ICustomPageConversationDomainService {

    /**
     * 保存用户会话记录
     */
    ReqResult<Long> saveConversation(CustomPageConversationModel model, UserContext userContext);

    /**
     * 保存助手会话记录（整轮结束一次性 insert，与 conversation_message 相同）
     */
    ReqResult<Long> saveAssistantConversation(CustomPageConversationModel model, UserContext userContext);

    /**
     * 根据项目ID查询会话记录列表
     */
    List<CustomPageConversationModel> listByProjectId(Long projectId, Long userId);

    /**
     * 分页查询会话记录
     */
    ReqResult<SuperPage<CustomPageConversationModel>> pageQuery(CustomPageConversationModel queryModel, Long current,
            Long pageSize, UserContext userContext);

    /**
     * 根据requestId回填用户消息的sessionId
     */
    ReqResult<Void> updateUserSessionIdByRequestId(Long projectId, String requestId, String sessionId, UserContext userContext);

    /**
     * 删除项目下的会话记录
     */
    ReqResult<Void> deleteByProjectId(Long projectId, UserContext userContext);
}
