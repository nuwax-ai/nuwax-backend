package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.AgentDetailDto;
import com.xspaceagi.agent.core.adapter.dto.CardDto;
import com.xspaceagi.agent.core.adapter.dto.UserAgentDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;

import java.util.List;

public interface AgentApplicationService {

    /**
     * 新增智能体
     *
     * @param agent
     */
    Long add(AgentConfigDto agent);

    /**
     * 更新智能体基础配置，AgentConfig上有的字段
     */
    void update(AgentConfigDto agentConfigDto);

    /**
     * 删除智能体，含配置以及发布信息、统计信息、用户收藏点赞信息
     *
     * @param agentId
     */
    void delete(Long agentId);

    /**
     * 复制智能体
     *
     * @param userId
     * @param agentId
     * @return
     */
    Long copyAgent(Long userId, Long agentId);

    /**
     * 复制智能体
     *
     * @param userId
     * @param agentConfigDto
     * @param targetSpaceId
     * @return
     */
    Long copyAgent(Long userId, AgentConfigDto agentConfigDto, Long targetSpaceId);

    Long importAgent(Long userId, AgentConfigDto agentConfigDto, Long targetSpaceId);

    /**
     * 移动智能体到其他空间
     */
    void transfer(Long userId, Long agentId, Long targetSpaceId);

    /**
     * 删除空间下的所有智能体
     *
     * @param spaceId
     */
    void deleteBySpaceId(Long spaceId);

    /**
     * 获取空间下的智能体列表
     */
    List<AgentConfigDto> queryListBySpaceId(Long spaceId);

    /**
     * 根据ID获取智能体列表
     *
     * @param agentIds
     * @return
     */
    List<AgentConfigDto> queryListByIds(List<Long> agentIds);

    /**
     * 根据ID获取智能体配置
     *
     * @param agentId
     * @return
     */
    AgentConfigDto queryById(Long agentId);

    AgentConfigDto queryByUid(String agentUid);

    AgentConfigDto queryAgentByIdWithStatics(Long agentId);

    /**
     * 添加组件配置
     */
    void addComponentConfig(AgentComponentConfigDto agentComponentConfigDto);

    /**
     * 更新组件配置
     *
     * @param agentComponentConfigDto
     */
    void updateComponentConfig(AgentComponentConfigDto agentComponentConfigDto);

    /**
     * 删除组件配置
     *
     * @param id 组件配置ID
     */
    void deleteComponentConfig(Long id);

    /**
     * 获取组件配置列表
     */
    List<AgentComponentConfigDto> queryComponentConfigList(Long agentId);

    /**
     * 获取组件配置
     *
     * @param id
     * @return
     */
    AgentComponentConfigDto queryComponentConfig(Long id);

    List<Arg> getAgentNoneSystemVariables(Long agentId, Long spaceId);

    /**
     * 获取完整的智能体配置，可选发布状态；执行使用
     */
    AgentConfigDto queryPublishedConfigForExecute(Long agentId);

    AgentConfigDto queryPublishedConfig(Long agentId, boolean execute);

    /**
     * 获取完整的智能体配置；测试使用
     */
    AgentConfigDto queryConfigForTestExecute(Long agentId);

    AgentDetailDto queryAgentDetail(Long agentId, boolean isPublished);

    /**
     * 获取卡片列表
     */
    List<CardDto> queryCardList();

    /**
     * 收藏
     */
    void collect(Long userId, Long agentId);

    /**
     * 取消收藏
     */
    void unCollect(Long userId, Long agentId);

    void devCollect(Long userId, Long agentId);

    void unDevCollect(Long userId, Long agentId);

    /**
     * 点赞
     */
    void like(Long userId, Long agentId);

    /**
     * 取消点赞
     */
    void unLike(Long userId, Long agentId);

    /**
     * 添加或更新最近使用的智能体
     *
     * @param userId
     * @param agentId
     */
    void addOrUpdateRecentUsed(Long userId, Long agentId);

    void addOrUpdateRecentUsed(Long userId, Long agentId, Long conversationId);

    /**
     * 获取用户最近编辑的智能体列表
     */
    List<UserAgentDto> queryRecentEditList(Long userId, Integer size);

    /**
     * 获取用户最近使用过的智能体列表
     */
    List<UserAgentDto> queryRecentUseList(Long userId, String kw, Integer size, Integer pageIndex);

    /**
     * 获取用户最近使用过的智能体列表
     */
    List<UserAgentDto> queryRecentUseList(Long userId, Integer size);

    UserAgentDto queryUserAgentRecentUse(Long userId, Long agentId);

    /**
     * 获取用户收藏的智能体列表
     */
    List<UserAgentDto> queryCollectionList(Long userId, Integer page, Integer size);

    /**
     * 开发收藏
     *
     * @param userId
     * @param page
     * @param size
     * @return
     */
    List<UserAgentDto> queryDevCollectionList(Long userId, Integer page, Integer size);

    /**
     * 为通用智能体构建MCP用于代理执行插件工作流知识库数据表
     */
    void buildProxyMcp(AgentConfigDto agentConfigDto, boolean isDev);


    void updateAccessControlStatus(Long agentId, Integer status);

    /**
     * 统计用户创建的智能体数量
     *
     * @param userId
     * @return
     */
    Long countUserCreatedAgent(Long userId);

    /**
     * 统计用户创建的网页应用数量
     */
    Long countUserCreatedPageApp(Long userId);

    /**
     * 获取用户可选择的模型列表
     *
     * @param userId 用户ID
     * @param agentId 智能体ID
     * @return 模型列表
     */
    List<ModelConfigDto> queryUserCanSelectModelListForAgent(Long userId, Long agentId);
}

