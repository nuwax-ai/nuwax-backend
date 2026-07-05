package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Card;
import com.xspaceagi.agent.core.adapter.repository.entity.ConfigHistory;

import java.util.List;

/**
 * 智能体领域服务，包含配置、发布、统计数据相关接口
 */
public interface AgentDomainService {

    /**
     * 新增智能体
     *
     * @param agent
     */
    void add(AgentConfig agent);

    /**
     * 删除智能体
     *
     * @param agentId
     */
    void delete(Long agentId);

    /**
     * 根据空间ID删除智能体
     *
     * @param spaceId
     */
    void deleteBySpaceId(Long spaceId);

    /**
     * 更新智能体
     *
     * @param agent
     */
    void update(AgentConfig agent);

    AgentConfig queryById(Long agentId);

    List<AgentConfig> queryListByIds(List<Long> agentIds);

    /**
     * 根据空间ID查询智能体列表
     */
    List<AgentConfig> queryListBySpaceId(Long spaceId);


    void transferUpdate(Long agentId, Long tempAgentId);

    /**
     * 复制智能体配置
     *
     * @param userId
     * @param agentId
     * @return
     */
    Long copyAgent(Long userId, Long agentId);

    /**
     * 查询可用的卡片列表
     *
     * @return
     */
    List<Card> queryCardList();

    /**
     * 新增智能体组件配置
     */
    void addAgentComponentConfig(AgentComponentConfig agentComponentConfig);

    /**
     * 更新智能体组件配置
     */
    void updateAgentComponentConfig(AgentComponentConfig agentComponentConfig);

    /**
     * 删除智能体组件配置
     */
    void deleteAgentComponentConfigById(Long id);

    /**
     * 删除智能体下所有组件配置
     */
    void deleteAgentComponentConfig(Long agentId);

    /**
     * 查询智能体组件配置
     *
     * @param id
     * @return
     */
    AgentComponentConfig queryComponentConfig(Long id);

    List<AgentComponentConfig> queryComponentConfigsByAgentIdAndType(Long agentId, AgentComponentConfig.Type type);

    /**
     * 查询智能体组件配置
     *
     * @param agentId
     * @return
     */
    List<AgentComponentConfig> queryAgentComponentConfigList(Long agentId);

    /**
     * 查询智能体配置记录
     */
    List<ConfigHistory> queryConfigHistoryList(Long agentId);

    /**
     * 统计用户创建的智能体数量
     */
    Long countUserCreatedAgent(Long userId);


    /**
     * 统计用户创建的网页应用数量
     */
    Long countUserCreatedPageApp(Long userId);

    AgentConfig queryByUid(String agentUid);
}
