package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.ConversationMessageRepository;
import com.xspaceagi.agent.core.adapter.repository.ConversationRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.ConversationMessage;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConversationDomainServiceImpl implements ConversationDomainService {

    private static final String DEFAULT_TOPIC = "Unnamed conversation";
    @Resource
    private ConversationRepository conversationRepository;

    @Resource
    private ConversationMessageRepository conversationMessageRepository;

    @PostConstruct
    public void init() {
        // 将正在执行中的会话状态改为已完成，避免服务器重启时状态一直展示执行中
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getTaskStatus, Conversation.ConversationTaskStatus.EXECUTING);
        queryWrapper.in(Conversation::getType, Conversation.ConversationType.Chat, Conversation.ConversationType.TempChat);
        Conversation conversation = new Conversation();
        conversation.setTaskStatus(Conversation.ConversationTaskStatus.COMPLETE);
        TenantFunctions.callWithIgnoreCheck(() -> conversationRepository.update(conversation, queryWrapper));
    }

    @Override
    public void createConversation(Conversation conversation) {
        conversation.setTaskStatus(Conversation.ConversationTaskStatus.CREATE);
        conversationRepository.save(conversation);
    }

    @Override
    public void deleteConversation(Long userId, Long id) {
        //通过用户id和id删除会话
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserId, userId);
        queryWrapper.eq(Conversation::getId, id);
        conversationRepository.remove(queryWrapper);
    }

    @Override
    public boolean updateConversation(Long userId, Long id, Conversation conversation) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserId, userId);
        queryWrapper.eq(Conversation::getId, id);
        return conversationRepository.update(conversation, queryWrapper);
    }


    @Override
    public boolean updateConversation(Long id, Conversation conversation) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getId, id);
        return conversationRepository.update(conversation, queryWrapper);
    }

    @Override
    public Conversation getConversation(Long userId, Long id) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            queryWrapper.eq(Conversation::getUserId, userId);
        }
        queryWrapper.eq(Conversation::getId, id);
        return conversationRepository.getOne(queryWrapper);
    }

    @Override
    public Conversation getConversationByUid(Long userId, String uid) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserId, userId);
        queryWrapper.eq(Conversation::getUid, uid);
        return conversationRepository.getOne(queryWrapper);
    }

    @Override
    public Conversation getConversationByUid(String uid) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUid, uid);
        return conversationRepository.getOne(queryWrapper);
    }

    @Override
    public Conversation getConversation(Long id) {
        if (id == null) {
            return null;
        }
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getId, id);
        return TenantFunctions.callWithIgnoreCheck(() -> conversationRepository.getOne(queryWrapper));
    }

    @Override
    public List<Conversation> queryConversationList(Long userId, Long agentId, Long lastId, int size, String topic) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserId, userId);
        queryWrapper.eq(Conversation::getDevMode, 0);
        queryWrapper.eq(Conversation::getTopicUpdated, 1);
        queryWrapper.eq(Conversation::getType, Conversation.ConversationType.Chat);
        if (StringUtils.isNotBlank(topic)) {
            queryWrapper.like(Conversation::getTopic, topic);
        }
        if (lastId == null || lastId <= 0) {
            lastId = Long.MAX_VALUE;
        }
        queryWrapper.lt(Conversation::getId, lastId);
        if (agentId != null) {
            queryWrapper.eq(Conversation::getAgentId, agentId);
        }
        queryWrapper.orderByDesc(Conversation::getModified);
        queryWrapper.last("LIMIT " + size);
        return conversationRepository.list(queryWrapper);
    }

    @Override
    public List<Conversation> queryTaskConversationList(Long userId, Long agentId, Conversation.ConversationTaskStatus status, int size) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserId, userId);
        queryWrapper.eq(Conversation::getTaskStatus, status);
        if (agentId != null) {
            queryWrapper.eq(Conversation::getAgentId, agentId);
        }
        queryWrapper.eq(Conversation::getType, Conversation.ConversationType.TASK);
        queryWrapper.orderByDesc(Conversation::getId);
        queryWrapper.last("LIMIT " + size);
        return conversationRepository.list(queryWrapper);
    }

    @Override
    public List<Conversation> queryConversationListBySandboxServerId(Long sandboxServerId) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getSandboxServerId, sandboxServerId);
        //最近25小时的会话
        queryWrapper.ge(Conversation::getCreated, DateUtils.addHours(new Date(), -25));
        return conversationRepository.list(queryWrapper);
    }

    @Override
    public long agentUserCount(Long userId, Long agentId) {
        QueryWrapper<Conversation> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("distinct user_id");
        if (userId != null) {
            queryWrapper.eq("user_id", userId);
        }
        queryWrapper.eq("dev_mode", 0);
        queryWrapper.ne("topic_updated", -1);
        if (agentId != null) {
            queryWrapper.eq("agent_id", agentId);
        }
        return conversationRepository.count(queryWrapper);
    }

    @Override
    public List<Long> queryAgentUserIdList(Long agentId, Long cursorUserId) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getAgentId, agentId);
        queryWrapper.gt(Conversation::getUserId, cursorUserId == null ? 0 : cursorUserId);
        queryWrapper.orderByAsc(Conversation::getUserId);
        queryWrapper.last("LIMIT 100");
        List<Conversation> conversations = conversationRepository.list(queryWrapper);
        if (conversations != null && !conversations.isEmpty()) {
            List<Long> collect = conversations.stream().map(Conversation::getUserId).collect(Collectors.toList());
            //collect去重
            return collect.stream().distinct().collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    public Long maxConversationId() {
        Conversation conversation = TenantFunctions.callWithIgnoreCheck(() -> {
            LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.orderByDesc(Conversation::getId);
            queryWrapper.last("LIMIT 1");
            return conversationRepository.getOne(queryWrapper);
        });
        if (conversation == null) {
            return 0L;
        }
        return conversation.getId();
    }

    @Override
    public Long nextConversationId(Long userId, Long agentId, String sandboxServerId) {
        Conversation conversation = new Conversation();
        conversation.setAgentId(agentId);
        conversation.setUserId(RequestContext.get().getUserId());
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic(DEFAULT_TOPIC);
        conversation.setDevMode(0);
        conversation.setTenantId(RequestContext.get().getTenantId());
        conversation.setType(Conversation.ConversationType.Chat);
        conversation.setTopicUpdated(-1);
        conversation.setSandboxServerId(sandboxServerId);
        createConversation(conversation);
        return conversation.getId();
    }

    @Override
    public Long addConversationMessage(ConversationMessage conversationMessage) {
        Assert.notNull(conversationMessage, "conversationMessage不能为空");
        Assert.notNull(conversationMessage.getConversationId(), "conversationId不能为空");
        Assert.notNull(conversationMessage.getUserId(), "userId不能为空");
        Assert.notNull(conversationMessage.getContent(), "content不能为空");
        Assert.notNull(conversationMessage.getTenantId(), "tenantId不能为空");
        if (conversationMessage.getMessageId() == null) {
            conversationMessage.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        }
        conversationMessageRepository.save(conversationMessage);
        return conversationMessage.getId();
    }

    @Override
    public ConversationMessage getConversationMessage(String messageId) {
        LambdaQueryWrapper<ConversationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConversationMessage::getMessageId, messageId);
        return conversationMessageRepository.getOne(queryWrapper);
    }

    @Override
    public boolean deleteConversationMessage(String messageId) {
        LambdaQueryWrapper<ConversationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConversationMessage::getMessageId, messageId);
        return conversationMessageRepository.remove(queryWrapper);
    }

    @Override
    public List<ConversationMessage> queryConversationMessageList(Long conversationId, Long lastId, int size) {
        LambdaQueryWrapper<ConversationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConversationMessage::getConversationId, conversationId);
        queryWrapper.orderByDesc(ConversationMessage::getId);
        queryWrapper.last("limit " + size);
        if (lastId == null) {
            lastId = Long.MAX_VALUE;
        }
        queryWrapper.lt(ConversationMessage::getId, lastId);
        return conversationMessageRepository.list(queryWrapper);
    }

    @Override
    public List<Conversation> queryLatestSandboxConversationList(List<Long> exceptSandboxIds) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.notIn(CollectionUtils.isNotEmpty(exceptSandboxIds), Conversation::getSandboxServerId, exceptSandboxIds);
        queryWrapper.isNotNull(Conversation::getSandboxServerId);
        queryWrapper.orderByDesc(Conversation::getSandboxSessionId);
        queryWrapper.ge(Conversation::getModified, DateUtils.addMinutes(new Date(), -10));
        return conversationRepository.list(queryWrapper);
    }
}
