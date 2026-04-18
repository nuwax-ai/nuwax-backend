package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.agent.core.adapter.repository.*;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;

@Service
public class AgentDomainServiceImpl implements AgentDomainService {

    @Resource
    private AgentConfigRepository agentConfigRepository;

    @Resource
    private CardRepository cardRepository;

    @Resource
    private AgentComponentConfigRepository agentComponentConfigRepository;

    @Resource
    private ConfigHistoryRepository agentConfigHistoryRepository;

    @Resource
    private UserTargetRelationRepository userTargetRelationRepository;

    @Resource
    private PublishDomainService publishDomainService;

    @Override
    @DSTransactional
    public void add(AgentConfig agent) {
        Assert.notNull(agent, "agent must be non-null");
        Assert.notNull(agent.getSpaceId(), "spaceId must be non-null");
        Assert.notNull(agent.getCreatorId(), "creatorId must be non-null");
        Assert.notNull(agent.getName(), "name must be non-null");
        agentConfigRepository.save(agent);
        //创建统计信息
        createStatistics(agent.getId());
    }

    private void createStatistics(Long id) {
        Map<String, Long> agentStatistics = new HashMap<>();
        agentStatistics.put(PublishedStatistics.Key.USER_COUNT.getKey(), 0L);
        agentStatistics.put(PublishedStatistics.Key.CONV_COUNT.getKey(), 0L);
        agentStatistics.put(PublishedStatistics.Key.COLLECT_COUNT.getKey(), 0L);
        agentStatistics.put(PublishedStatistics.Key.LIKE_COUNT.getKey(), 0L);
        publishDomainService.addPublishedStatistics(Published.TargetType.Agent, id, agentStatistics);
    }

    @Override
    @DSTransactional
    public void delete(Long agentId) {
        agentConfigRepository.removeById(agentId);
        agentComponentConfigRepository.remove(new QueryWrapper<>(AgentComponentConfig.builder().agentId(agentId).build()));
        agentConfigHistoryRepository.remove(new QueryWrapper<>(ConfigHistory.builder().targetType(Published.TargetType.Agent).targetId(agentId).build()));
        userTargetRelationRepository.remove(new QueryWrapper<>(UserTargetRelation.builder().targetType(Published.TargetType.Agent).targetId(agentId).build()));
        publishDomainService.deleteByTargetId(Published.TargetType.Agent, agentId);
    }

    @Override
    @DSTransactional
    public void deleteBySpaceId(Long spaceId) {
        queryListBySpaceId(spaceId).forEach(agentConfig -> {
            delete(agentConfig.getId());
        });
    }

    @Override
    public void update(AgentConfig agent) {
        Assert.notNull(agent, "agent must be non-null");
        Assert.notNull(agent.getId(), "id must be non-null");
        if (agent.getIcon() != null && agent.getIcon().contains("api/logo")) {
            agent.setIcon(null);
        }
        agentConfigRepository.updateById(agent);
    }

    @Override
    public AgentConfig queryById(Long agentId) {
        return agentConfigRepository.getById(agentId);
    }

    @Override
    public List<AgentConfig> queryListByIds(List<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<AgentConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(AgentConfig::getId, agentIds);
        return agentConfigRepository.list(queryWrapper);
    }

    @Override
    public List<AgentConfig> queryListBySpaceId(Long spaceId) {
        if (spaceId == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<AgentConfig> queryWrapper = new LambdaQueryWrapper<>(AgentConfig.builder().spaceId(spaceId).build());
        queryWrapper.ne(AgentConfig::getType, "PageApp");
        queryWrapper.orderByDesc(AgentConfig::getModified);
        return agentConfigRepository.list(queryWrapper);
    }

    @Override
    public void transferUpdate(Long agentId, Long tempAgentId) {
        Assert.notNull(agentId, "agentId must be non-null");
        Assert.notNull(tempAgentId, "targetSpaceId must be non-null");
        agentConfigRepository.removeById(agentId);
        LambdaUpdateWrapper<AgentConfig> agentConfigLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        agentConfigLambdaUpdateWrapper.eq(AgentConfig::getId, tempAgentId);
        agentConfigLambdaUpdateWrapper.set(AgentConfig::getId, agentId);
        agentConfigRepository.update(agentConfigLambdaUpdateWrapper);
        agentConfigRepository.removeById(tempAgentId);
    }

    @Override
    @DSTransactional
    public Long copyAgent(Long userId, Long agentId) {
        Assert.notNull(userId, "userId must be non-null");
        Assert.notNull(agentId, "agentId must be non-null");
        AgentConfig agentConfig = agentConfigRepository.getById(agentId);
        if (agentConfig == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentIdInvalid);
        }

        AgentConfig newAgentConfig = new AgentConfig();
        BeanUtils.copyProperties(agentConfig, newAgentConfig);
        newAgentConfig.setCreated(null);
        newAgentConfig.setModified(null);
        newAgentConfig.setCreatorId(userId);
        newAgentConfig.setId(null);
        newAgentConfig.setDevConversationId(null);
        newAgentConfig.setUid(UUID.randomUUID().toString().replace("-", ""));
        newAgentConfig.setName(newAgentConfig.getName() + "副本");
        newAgentConfig.setPublishStatus(Published.PublishStatus.Developing);
        agentConfigRepository.save(newAgentConfig);
        createStatistics(newAgentConfig.getId());

        queryAgentComponentConfigList(agentId).forEach(agentComponentConfig -> {
            AgentComponentConfig newAgentComponentConfig = new AgentComponentConfig();
            BeanUtils.copyProperties(agentComponentConfig, newAgentComponentConfig);
            newAgentComponentConfig.setId(null);
            newAgentComponentConfig.setAgentId(newAgentConfig.getId());
            agentComponentConfigRepository.save(newAgentComponentConfig);
        });
        return newAgentConfig.getId();
    }

    @Override
    public List<Card> queryCardList() {
        return cardRepository.list();
    }

    @Override
    public void addAgentComponentConfig(AgentComponentConfig agentComponentConfig) {
        Assert.notNull(agentComponentConfig, "agentComponentConfig must be non-null");
        Assert.notNull(agentComponentConfig.getAgentId(), "agentId must be non-null");
        Assert.notNull(agentComponentConfig.getType(), "type must be non-null");
        Assert.notNull(agentComponentConfig.getTargetId(), "targetId must be non-null");
        agentComponentConfig.setIcon(null);
        agentComponentConfigRepository.save(agentComponentConfig);
    }

    @Override
    public void updateAgentComponentConfig(AgentComponentConfig agentComponentConfig) {
        Assert.notNull(agentComponentConfig, "agentComponentConfig must be non-null");
        Assert.notNull(agentComponentConfig.getId(), "id must be non-null");
        agentComponentConfig.setIcon(null);
        agentComponentConfigRepository.updateById(agentComponentConfig);
    }

    @Override
    public void deleteAgentComponentConfigById(Long id) {
        agentComponentConfigRepository.removeById(id);
    }

    @Override
    public void deleteAgentComponentConfig(Long agentId) {
        agentComponentConfigRepository.remove(new QueryWrapper<>(AgentComponentConfig.builder().agentId(agentId).build()));
    }

    @Override
    public AgentComponentConfig queryComponentConfig(Long id) {
        return agentComponentConfigRepository.getById(id);
    }

    @Override
    public List<AgentComponentConfig> queryAgentComponentConfigList(Long agentId) {
        return agentComponentConfigRepository.list(new QueryWrapper<>(AgentComponentConfig.builder().agentId(agentId).build()));
    }

    @Override
    public List<ConfigHistory> queryConfigHistoryList(Long agentId) {
        Assert.notNull(agentId, "agentId must be non-null");
        QueryWrapper<ConfigHistory> queryWrapper = new QueryWrapper<>(ConfigHistory.builder().targetType(Published.TargetType.Agent).targetId(agentId).build());
        queryWrapper.orderByDesc("id");
        return agentConfigHistoryRepository.list(queryWrapper);
    }

    @Override
    public Long countUserCreatedAgent(Long userId) {
        LambdaQueryWrapper<AgentConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AgentConfig::getCreatorId, userId);
        queryWrapper.ne(AgentConfig::getType, "PageApp");
        return agentConfigRepository.count(queryWrapper);
    }

    @Override
    public Long countUserCreatedPageApp(Long userId) {
        LambdaQueryWrapper<AgentConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AgentConfig::getCreatorId, userId);
        queryWrapper.eq(AgentConfig::getType, "PageApp");
        return agentConfigRepository.count(queryWrapper);
    }

    @Override
    public AgentConfig queryByUid(String agentUid) {
        return agentConfigRepository.getOne(new QueryWrapper<>(AgentConfig.builder().uid(agentUid).build()));
    }
}
