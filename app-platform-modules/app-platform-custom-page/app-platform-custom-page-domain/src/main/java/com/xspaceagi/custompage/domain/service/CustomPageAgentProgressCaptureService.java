package com.xspaceagi.custompage.domain.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.gateway.AiAgentClient;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.util.AgentProgressContextUtil;
import com.xspaceagi.custompage.domain.util.AgentProgressEventUtil;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 进度：由 {@code /ai-session-sse} 触发订阅 Agent SSE；前端断开后仍保持 Agent 连接直至终态并落库；
 * 实时事件经内存转发给已连接的前端，不从 DB 推送给 SSE。
 */
@Slf4j
@Service
public class CustomPageAgentProgressCaptureService {

    private static final long SSE_TIMEOUT_MS = 10L * 60 * 1000;
    private static final long FRONTEND_ATTACH_POLL_MS = 300L;
    /** 增量落库：每 N 条可持久化事件或间隔 T 触发一次（终态事件与流结束仍立即落库）。 */
    private static final int PERSIST_EVERY_N_EVENTS = 20;
    private static final long PERSIST_INTERVAL_MS = 15_000L;

    private final ConcurrentHashMap<String, CaptureContext> activeCaptures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sessionStartLocks = new ConcurrentHashMap<>();
    /** 前端 SSE 早于后台采集就绪时暂挂，采集启动后挂到实时 emitters（非 DB）。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> pendingFrontendEmitters =
            new ConcurrentHashMap<>();

    private final static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Resource
    private AiAgentClient aiAgentClient;
    @Resource
    private ICustomPageConversationDomainService customPageConversationDomainService;
    @Resource
    private AgentProgressSessionCoordinator sessionCoordinator;
    @Resource
    @Qualifier("aiAgentProgressScheduler")
    private ScheduledExecutorService aiAgentProgressScheduler;

    /**
     * 前端连接时启动（或挂接）Agent SSE 采集；断开前端连接不结束 Agent 订阅。
     *
     * @param fluxRequestId 与 ai-chat-flux 一致的 request_id，可空则从 DB 最新 USER 推断
     */
    public SseEmitter attachFrontendSse(String sessionId, Long projectId, UserContext userContext,
            String fluxRequestId) {
        if (StringUtils.isBlank(sessionId) || projectId == null || projectId <= 0) {
            throw new IllegalArgumentException("sessionId and projectId are required");
        }
        String resolvedRequestId = resolveFluxRequestId(projectId, sessionId, fluxRequestId, userContext);

        CaptureContext active = findLatestActiveCaptureByAgentSession(projectId, sessionId);
        if (active == null && StringUtils.isNotBlank(resolvedRequestId)) {
            active = getOrStartCapture(sessionId, projectId, userContext, resolvedRequestId);
        }
        if (active != null) {
            return attachEmitterToCapture(sessionId, active);
        }
        if (StringUtils.isNotBlank(resolvedRequestId)) {
            log.warn(
                    "[Agent Progress] cannot start capture (lock held elsewhere?), waiting, project Id={}, session Id={}, request Id={}",
                    projectId, sessionId, resolvedRequestId);
        } else {
            log.warn("[Agent Progress] missing request Id, cannot start capture, project Id={}, session Id={}",
                    projectId, sessionId);
        }
        return attachWaitingForLiveCapture(sessionId, projectId);
    }

    private String resolveFluxRequestId(Long projectId, String sessionId, String fluxRequestId,
            UserContext userContext) {
        if (StringUtils.isNotBlank(fluxRequestId)) {
            return fluxRequestId;
        }
        return AgentProgressContextUtil.callWithUserContext(userContext,
                () -> sessionCoordinator.resolvePendingTurnRequestId(projectId, sessionId));
    }

    private SseEmitter attachEmitterToCapture(String sessionId, CaptureContext active) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        active.emitters.add(emitter);
        replayBufferedEvents(active, emitter);
        registerEmitterCallbacks(sessionId, emitter, active);
        return emitter;
    }

    private SseEmitter attachWaitingForLiveCapture(String sessionId, Long projectId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String waitKey = buildSessionWaitKey(projectId, sessionId);
        pendingFrontendEmitters.computeIfAbsent(waitKey, key -> new CopyOnWriteArrayList<>()).add(emitter);
        log.info("[Agent Progress] frontend SSE waiting for live capture, project Id={}, session Id={}", projectId,
                sessionId);

        AtomicBoolean attached = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> pollRef = new AtomicReference<>();
        Runnable detachPending = () -> pendingFrontendEmitters.computeIfPresent(waitKey, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
        Runnable tryAttachToCapture = () -> {
            if (attached.get()) {
                return;
            }
            CaptureContext context = findLatestActiveCaptureByAgentSession(projectId, sessionId);
            if (context == null) {
                return;
            }
            detachPending.run();
            context.emitters.add(emitter);
            replayBufferedEvents(context, emitter);
            registerEmitterCallbacks(sessionId, emitter, context);
            attached.set(true);
            cancelPollTask(pollRef.get());
            log.info("[Agent Progress] frontend SSE attached to live capture, project Id={}, session Id={}, request Id={}",
                    projectId, sessionId, context.fluxRequestId);
        };

        tryAttachToCapture.run();
        if (!attached.get()) {
            pollRef.set(aiAgentProgressScheduler.scheduleAtFixedRate(tryAttachToCapture, FRONTEND_ATTACH_POLL_MS,
                    FRONTEND_ATTACH_POLL_MS, TimeUnit.MILLISECONDS));
        }

        Runnable cleanup = () -> {
            attached.set(true);
            cancelPollTask(pollRef.get());
            detachPending.run();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());
        return emitter;
    }

    private void attachPendingFrontendEmitters(CaptureContext context) {
        String waitKey = buildSessionWaitKey(context.projectId, context.sessionId);
        CopyOnWriteArrayList<SseEmitter> pending = pendingFrontendEmitters.remove(waitKey);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : pending) {
            context.emitters.add(emitter);
            replayBufferedEvents(context, emitter);
            registerEmitterCallbacks(context.sessionId, emitter, context);
        }
        log.info("[Agent Progress] attached {} pending frontend SSE to live capture, session Id={}, request Id={}",
                pending.size(), context.sessionId, context.fluxRequestId);
    }

    private static String buildSessionWaitKey(Long projectId, String sessionId) {
        return projectId + ":" + sessionId;
    }

    private CaptureContext findLatestActiveCaptureByAgentSession(Long projectId, String agentSessionId) {
        CaptureContext latest = null;
        for (CaptureContext context : activeCaptures.values()) {
            if (!projectId.equals(context.projectId) || !agentSessionId.equals(context.sessionId)) {
                continue;
            }
            if (latest == null || context.startedAtMs > latest.startedAtMs) {
                latest = context;
            }
        }
        return latest;
    }

    private CaptureContext findCaptureForEvent(Long projectId, String sessionId, String eventRequestId) {
        if (StringUtils.isNotBlank(eventRequestId)) {
            CaptureContext byRequest = activeCaptures.get(buildCaptureKey(projectId, eventRequestId));
            if (byRequest != null) {
                return byRequest;
            }
        }
        return findLatestActiveCaptureByAgentSession(projectId, sessionId);
    }

    private CaptureContext getOrStartCapture(String sessionId, Long projectId, UserContext userContext,
            String fluxRequestId) {
        String captureKey = buildCaptureKey(projectId, fluxRequestId);
        CaptureContext existing = activeCaptures.get(captureKey);
        if (existing != null) {
            return existing;
        }
        Object lock = sessionStartLocks.computeIfAbsent(captureKey, key -> new Object());
        synchronized (lock) {
            try {
                existing = activeCaptures.get(captureKey);
                if (existing != null) {
                    return existing;
                }
                if (!sessionCoordinator.tryAcquireCaptureLock(projectId, fluxRequestId)) {
                    log.info("[Agent Progress] capture lock held by another instance, project Id={}, request Id={}",
                            projectId, fluxRequestId);
                    return null;
                }
                sessionCoordinator.clearTurnDone(projectId, fluxRequestId);
                CaptureContext context = new CaptureContext(projectId, sessionId, userContext, fluxRequestId);
                activeCaptures.put(captureKey, context);
                attachPendingFrontendEmitters(context);
                startAgentSubscription(sessionId, projectId, userContext, fluxRequestId);
                log.info("[Agent Progress] started server-side capture, project Id={}, session Id={}, request Id={}",
                        projectId, sessionId, fluxRequestId);
                return context;
            } catch (RuntimeException e) {
                activeCaptures.remove(captureKey);
                sessionCoordinator.releaseCaptureLock(projectId, fluxRequestId);
                throw e;
            } catch (Exception e) {
                activeCaptures.remove(captureKey);
                sessionCoordinator.releaseCaptureLock(projectId, fluxRequestId);
                throw new RuntimeException(e);
            }
        }
    }

    private void startAgentSubscription(String sessionId, Long projectId, UserContext userContext,
            String fluxRequestId) {
        aiAgentClient.subscribeSessionSse(sessionId, null, projectId, userContext,
                (eventName, data) -> onAgentEvent(sessionId, projectId, eventName, data),
                () -> onAgentStreamFinished(projectId, fluxRequestId),
                errorMessage -> onSubscribeFailed(projectId, fluxRequestId, errorMessage));
    }

    private void onAgentEvent(String sessionId, Long projectId, String eventName, String data) {
        String eventRequestId = extractRequestId(data);
        CaptureContext context = findCaptureForEvent(projectId, sessionId, eventRequestId);
        if (context == null) {
            return;
        }
        if (!AgentProgressEventUtil.shouldPersist(eventName)) {
            broadcastToEmitters(context, eventName, data);
            return;
        }
        Map<String, Object> eventRecord = new HashMap<>();
        eventRecord.put("event", eventName);
        eventRecord.put("data", AgentProgressEventUtil.parseRawData(data));
        context.events.add(eventRecord);

        if (StringUtils.isNotBlank(eventRequestId)) {
            context.requestIdRef.set(eventRequestId);
        }

        broadcastToEmitters(context, eventName, data);
        maybePersistIncremental(context);

        if (AgentProgressEventUtil.isFinalEvent(eventName, data)) {
            persistAssistantConversation(context, true);
        }
    }

    private void maybePersistIncremental(CaptureContext context) {
        int eventCount = context.events.size();
        long now = System.currentTimeMillis();
        boolean byCount = eventCount > 0 && eventCount % PERSIST_EVERY_N_EVENTS == 0;
        boolean byInterval = now - context.lastPersistAtMs.get() >= PERSIST_INTERVAL_MS;
        if (!byCount && !byInterval) {
            return;
        }
        context.lastPersistAtMs.set(now);
        persistAssistantConversation(context, false);
    }

    private void onAgentStreamFinished(Long projectId, String fluxRequestId) {
        CaptureContext context = activeCaptures.get(buildCaptureKey(projectId, fluxRequestId));
        if (context == null) {
            sessionCoordinator.releaseCaptureLock(projectId, fluxRequestId);
            return;
        }
        log.info("[Agent Progress] AI Agent SSE stream finished, project Id={}, session Id={}, request Id={}, eventCount={}",
                context.projectId, context.sessionId, context.fluxRequestId, context.events.size());
        finishCapture(context);
    }

    private void finishCapture(CaptureContext context) {
        String captureKey = buildCaptureKey(context.projectId, context.fluxRequestId);
        if (activeCaptures.remove(captureKey) == null) {
            return;
        }
        sessionStartLocks.remove(captureKey);
        persistAssistantConversation(context, false);
        if (isContextTerminal(context)) {
            sessionCoordinator.markTurnDone(context.projectId, context.fluxRequestId);
        }
        sessionCoordinator.releaseCaptureLock(context.projectId, context.fluxRequestId);
        completeEmitters(context);
    }

    private void onSubscribeFailed(Long projectId, String fluxRequestId, String errorMessage) {
        CaptureContext context = activeCaptures.get(buildCaptureKey(projectId, fluxRequestId));
        if (context == null) {
            sessionCoordinator.releaseCaptureLock(projectId, fluxRequestId);
            return;
        }
        log.warn("[Agent Progress] subscribe failed, project Id={}, session Id={}, request Id={}, error={}",
                context.projectId, context.sessionId, fluxRequestId, errorMessage);
        broadcastErrorToEmitters(context, errorMessage);
        finishCapture(context);
    }

    private boolean isContextTerminal(CaptureContext context) {
        synchronized (context.events) {
            for (Map<String, Object> record : context.events) {
                Object eventName = record.get("event");
                Object data = record.get("data");
                String dataStr = data instanceof String ? (String) data : JSON.toJSONString(data);
                if (AgentProgressEventUtil.isFinalEvent(
                        eventName == null ? null : String.valueOf(eventName), dataStr)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void cancelPollTask(ScheduledFuture<?> pollTask) {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
    }

    /** 每轮用户提问对应唯一 requestId，采集实例按 projectId+requestId 隔离。 */
    private static String buildCaptureKey(Long projectId, String fluxRequestId) {
        return projectId + ":" + fluxRequestId;
    }

    /** 前端断开仅移除 emitter，不结束 Agent 订阅与采集。 */
    private void registerEmitterCallbacks(String sessionId, SseEmitter emitter, CaptureContext context) {
        emitter.onCompletion(() -> {
            context.emitters.remove(emitter);
            log.info("[Agent Progress] frontend SSE disconnected, agent capture continues, session Id={}, request Id={}",
                    sessionId, context.fluxRequestId);
        });
        emitter.onTimeout(() -> {
            context.emitters.remove(emitter);
            log.warn("[Agent Progress] frontend SSE timeout, agent capture continues, session Id={}, request Id={}",
                    sessionId, context.fluxRequestId);
        });
        emitter.onError(throwable -> {
            context.emitters.remove(emitter);
            log.warn("[Agent Progress] frontend SSE error, agent capture continues, session Id={}, request Id={}",
                    sessionId, context.fluxRequestId, throwable);
        });
    }

    /** 本轮已从 Agent SSE 收到、尚未推给该前端连接的事件（内存缓冲，非 DB）。 */
    private void replayBufferedEvents(CaptureContext context, SseEmitter emitter) {
        List<Map<String, Object>> snapshot;
        synchronized (context.events) {
            snapshot = new ArrayList<>(context.events);
        }
        for (Map<String, Object> record : snapshot) {
            try {
                Object eventName = record.get("event");
                Object dataObj = record.get("data");
                String data = dataObj instanceof String ? (String) dataObj : JSON.toJSONString(dataObj);
                SseEmitter.SseEventBuilder builder = SseEmitter.event();
                if (eventName != null) {
                    builder.name(String.valueOf(eventName));
                }
                builder.data(data);
                emitter.send(builder);
            } catch (Exception e) {
                log.debug("[Agent Progress] replay event to frontend failed", e);
                break;
            }
        }
    }

    private void broadcastToEmitters(CaptureContext context, String eventName, String data) {
        if (context.emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : context.emitters) {
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event();
                if (eventName != null) {
                    builder.name(eventName);
                }
                builder.data(data);
                emitter.send(builder);
            } catch (Exception e) {
                log.debug("[Agent Progress] forward event to frontend failed", e);
            }
        }
    }

    private void broadcastErrorToEmitters(CaptureContext context, String errorMessage) {
        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("type", "error");
        errorBody.put("code", "9999");
        errorBody.put("message", errorMessage);
        String data = JSON.toJSONString(errorBody);
        broadcastToEmitters(context, "error", data);
    }

    private void completeEmitters(CaptureContext context) {
        for (SseEmitter emitter : context.emitters) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
        }
        context.emitters.clear();
    }

    private void persistAssistantConversation(CaptureContext context, boolean markDoneIfTerminal) {
        synchronized (context.persistLock) {
            List<Map<String, Object>> snapshot;
            synchronized (context.events) {
                if (context.events.isEmpty()) {
                    return;
                }
                snapshot = new ArrayList<>(context.events);
            }
            try {
                AgentProgressContextUtil.runWithUserContext(context.userContext, () -> {
                    CustomPageConversationModel model = new CustomPageConversationModel();
                    model.setProjectId(context.projectId);
                    model.setTopic("Assistant");
                    model.setContent(JSON.toJSONString(Map.of("events", snapshot)));
                    model.setRole("ASSISTANT");
                    model.setSessionId(context.sessionId);
                    String requestId = StringUtils.isNotBlank(context.fluxRequestId) ? context.fluxRequestId
                            : context.requestIdRef.get();
                    model.setRequestId(requestId);
                    Long assistantRecordId = context.assistantRecordIdRef.get();
                    if (assistantRecordId != null) {
                        model.setId(assistantRecordId);
                    }
                    ReqResult<Long> result = customPageConversationDomainService.saveOrUpdateAssistantConversation(
                            model, context.userContext);
                    if (result != null && result.isSuccess() && result.getData() != null) {
                        context.assistantRecordIdRef.compareAndSet(null, result.getData());
                    }
                    if (result == null || !result.isSuccess()) {
                        String msg = result == null ? "unknown" : result.getMessage();
                        log.error("[Agent Progress] persist assistant failed, project Id={}, request Id={}, error={}",
                                context.projectId, context.fluxRequestId, msg);
                        return;
                    }
                    if (markDoneIfTerminal && isEventsTerminal(snapshot)) {
                        sessionCoordinator.markTurnDone(context.projectId, context.fluxRequestId);
                    }
                });
            } catch (Exception e) {
                log.error("[Agent Progress] persist assistant conversation failed, project Id={}, request Id={}",
                        context.projectId, context.fluxRequestId, e);
            }
        }
    }

    private boolean isEventsTerminal(List<Map<String, Object>> events) {
        for (Map<String, Object> record : events) {
            Object eventName = record.get("event");
            Object data = record.get("data");
            String dataStr = data instanceof String ? (String) data : JSON.toJSONString(data);
            if (AgentProgressEventUtil.isFinalEvent(eventName == null ? null : String.valueOf(eventName), dataStr)) {
                return true;
            }
        }
        return false;
    }

    private String extractRequestId(String data) {
        if (!JSON.isValidObject(data)) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            Object requestId = payload.get("request_id");
            if (requestId != null && StringUtils.isNotBlank(String.valueOf(requestId))) {
                return String.valueOf(requestId);
            }
            Object dataObj = payload.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                Object requestIdInData = dataMap.get("request_id");
                if (requestIdInData != null && StringUtils.isNotBlank(String.valueOf(requestIdInData))) {
                    return String.valueOf(requestIdInData);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class CaptureContext {
        private final Long projectId;
        private final String sessionId;
        private final String fluxRequestId;
        private final UserContext userContext;
        private final long startedAtMs = System.currentTimeMillis();
        private final Object persistLock = new Object();
        private final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> requestIdRef = new AtomicReference<>();
        private final AtomicReference<Long> assistantRecordIdRef = new AtomicReference<>();
        private final AtomicLong lastPersistAtMs = new AtomicLong(0);

        private CaptureContext(Long projectId, String sessionId, UserContext userContext, String fluxRequestId) {
            this.projectId = projectId;
            this.sessionId = sessionId;
            this.userContext = userContext;
            this.fluxRequestId = fluxRequestId;
        }
    }
}
