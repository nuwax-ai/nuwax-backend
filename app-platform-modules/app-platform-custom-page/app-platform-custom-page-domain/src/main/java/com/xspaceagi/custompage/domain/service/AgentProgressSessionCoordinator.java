package com.xspaceagi.custompage.domain.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageConversationRepository;
import com.xspaceagi.custompage.domain.util.AgentProgressEventUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 跨实例协调 Agent 进度采集锁与终态标记（键含 projectId + requestId，按轮隔离）。
 */
@Slf4j
@Component
public class AgentProgressSessionCoordinator {

    private static final String CAPTURE_LOCK_PREFIX = "custom.page.agent.progress.lock.";
    private static final String DONE_PREFIX = "custom.page.agent.progress.done.";
    private static final int CAPTURE_LOCK_TTL_SECONDS = 7200;
    private static final int DONE_TTL_SECONDS = 86400;

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private ICustomPageConversationRepository customPageConversationRepository;

    public boolean tryAcquireCaptureLock(Long projectId, String requestId) {
        if (projectId == null || StringUtils.isBlank(requestId)) {
            return false;
        }
        return redisUtil.setIfAbsent(buildLockKey(projectId, requestId), lockOwner(), CAPTURE_LOCK_TTL_SECONDS);
    }

    public void releaseCaptureLock(Long projectId, String requestId) {
        if (projectId == null || StringUtils.isBlank(requestId)) {
            return;
        }
        String key = buildLockKey(projectId, requestId);
        Object holder = redisUtil.get(key);
        if (holder != null && lockOwner().equals(String.valueOf(holder))) {
            redisUtil.expire(key, 0);
        }
    }

    public void markTurnDone(Long projectId, String requestId) {
        if (projectId == null || StringUtils.isBlank(requestId)) {
            return;
        }
        redisUtil.set(buildDoneKey(projectId, requestId), "1", DONE_TTL_SECONDS);
    }

    public void clearTurnDone(Long projectId, String requestId) {
        if (projectId == null || StringUtils.isBlank(requestId)) {
            return;
        }
        redisUtil.expire(buildDoneKey(projectId, requestId), 0);
    }

    /**
     * 当前待回复轮次的 requestId（与 ai-chat-flux 一致），无则 null。
     */
    public String resolvePendingTurnRequestId(Long projectId, String sessionId) {
        CustomPageConversationModel pendingUser = resolveLatestPendingUser(projectId, sessionId);
        return pendingUser == null ? null : pendingUser.getRequestId();
    }

    /** 新一轮 /chat 接受后清除该轮 Redis 终态，便于重新采集。 */
    public void prepareForNewTurn(Long projectId, String requestId) {
        clearTurnDone(projectId, requestId);
    }

    private CustomPageConversationModel resolveLatestPendingUser(Long projectId, String agentSessionId) {
        if (projectId == null || StringUtils.isBlank(agentSessionId)) {
            return null;
        }
        CustomPageConversationModel latestUser = customPageConversationRepository
                .findLatestUserBySessionId(projectId, agentSessionId);
        if (latestUser == null) {
            latestUser = customPageConversationRepository.findLatestUserByProjectId(projectId);
        }
        if (latestUser == null || StringUtils.isBlank(latestUser.getRequestId())) {
            return null;
        }
        if (isUserTurnStillInProgress(projectId, latestUser)) {
            return latestUser;
        }
        return null;
    }

    private boolean isUserTurnStillInProgress(Long projectId, CustomPageConversationModel user) {
        CustomPageConversationModel assistantForTurn = customPageConversationRepository
                .findAssistantByProjectIdAndRequestId(projectId, user.getRequestId());
        if (assistantForTurn == null) {
            return true;
        }
        return !AgentProgressEventUtil.isAssistantContentTerminal(assistantForTurn.getContent());
    }

    private String buildLockKey(Long projectId, String requestId) {
        return CAPTURE_LOCK_PREFIX + projectId + "." + requestId;
    }

    private String buildDoneKey(Long projectId, String requestId) {
        return DONE_PREFIX + projectId + "." + requestId;
    }

    private String lockOwner() {
        String pod = System.getenv("POD_NAME");
        if (StringUtils.isBlank(pod)) {
            pod = System.getenv("HOSTNAME");
        }
        if (StringUtils.isBlank(pod)) {
            try {
                pod = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                pod = "unknown-host";
            }
        }
        return pod + "-" + ProcessHandle.current().pid();
    }
}
