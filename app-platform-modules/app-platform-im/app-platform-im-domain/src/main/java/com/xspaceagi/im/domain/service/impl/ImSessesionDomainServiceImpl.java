package com.xspaceagi.im.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.im.domain.repository.ImSessionRepository;
import com.xspaceagi.im.domain.service.ImSessionDomainService;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class ImSessesionDomainServiceImpl implements ImSessionDomainService {

    @Resource
    private ImSessionRepository imSessionRepository;

    @Override
    public ImSession findSession(ImSession imSession) {
        checkParams(imSession);

        LambdaQueryWrapper<ImSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImSession::getChannel, imSession.getChannel())
                .eq(ImSession::getTargetType, imSession.getTargetType())
                .eq(ImSession::getSessionKey, imSession.getSessionKey())
                .eq(ImSession::getAgentId, imSession.getAgentId())
                .eq(ImSession::getTenantId, imSession.getTenantId());
        return imSessionRepository.getOne(wrapper);
    }

    @Override
    public ImSession saveSession(ImSession imSession) {
        checkParams(imSession);

        ImSession existing = findSession(imSession);

        if (existing != null) {
            // 更新
            existing.setSessionName(imSession.getSessionName());
            existing.setChatType(imSession.getChatType());
            existing.setUserId(imSession.getUserId());
            existing.setConversationId(imSession.getConversationId());
            imSessionRepository.updateById(existing);
            return existing;
        } else {
            // 新增
            imSessionRepository.save(imSession);
            return imSession;
        }
    }

    @Override
    public boolean deleteSession(ImSession imSession) {
        checkParams(imSession);

        LambdaQueryWrapper<ImSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImSession::getChannel, imSession.getChannel())
                .eq(ImSession::getTargetType, imSession.getTargetType())
                .eq(ImSession::getSessionKey, imSession.getSessionKey())
                .eq(ImSession::getAgentId, imSession.getAgentId())
                .eq(ImSession::getTenantId, imSession.getTenantId());
        return imSessionRepository.remove(wrapper);
    }

    private void checkParams(ImSession imSession) {
        Assert.notNull(imSession, "imSession不能为空");
        Assert.notNull(imSession.getChannel(), "imSession.channel不能为空");
        Assert.notNull(imSession.getTargetType(), "imSession.targetType不能为空");
        Assert.notNull(imSession.getSessionKey(), "imSession.sessionKey不能为空");
        Assert.notNull(imSession.getAgentId(), "imSession.agentId不能为空");
        Assert.notNull(imSession.getTenantId(), "imSession.tenantId不能为空");
    }
}
