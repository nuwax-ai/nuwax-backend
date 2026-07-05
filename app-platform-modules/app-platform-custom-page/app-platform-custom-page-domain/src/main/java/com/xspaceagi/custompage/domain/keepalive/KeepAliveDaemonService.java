package com.xspaceagi.custompage.domain.keepalive;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.gateway.PageAppFileClient;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 保活守护程序
 */
@Slf4j
@Service
public class KeepAliveDaemonService {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private PageAppFileClient pageAppFileClient;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 保活超时时间：5分钟
    private static final long KEEPALIVE_TIMEOUT_MS = 5 * 60 * 1000;

    // 定时检查间隔：20秒
    private static final long CHECK_INTERVAL_SECONDS = 20;

    // Redis key前缀
    private static final String KEEPALIVE_KEY_PREFIX = "dev:keepalive:";

    // 分布式锁key
    private static final String KEEPALIVE_LOCK_KEY = "dev:keepalive:lock:check";

    // 所有保活项目ID集合的key
    private static final String KEEPALIVE_PROJECTS_SET_KEY = "dev:keepalive:projects";

    private ScheduledExecutorService scheduledExecutor;

    @PostConstruct
    public void init() {
        // 启动定时检查任务
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dev-keepalive-checker");
            thread.setDaemon(true);
            return thread;
        });

        // 启动时从数据库加载 devRunning=1 的项目到缓存
        try {
            TenantFunctions.callWithIgnoreCheck(this::warmupKeepAliveCache);
        } catch (Exception e) {
            log.error("[Keep Alive-daemon] warm-up Rediskeep-alivecachefailed", e);
        }

        scheduledExecutor.scheduleWithFixedDelay(
                this::checkKeepAliveTimeout,
                CHECK_INTERVAL_SECONDS,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("[Keep Alive-daemon] keep-aliveserviceinitializecompleted, scheduled checkinterval: {}seconds", CHECK_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void destroy() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[Keep Alive-daemon] keep-aliveserviceclosed");
    }

    /**
     * 启动时预热Redis保活缓存：从数据库导入 devRunning=1 的项目
     */
    private Integer warmupKeepAliveCache() {
        // 查询运行中的项目
        List<CustomPageBuildModel> runningList = customPageBuildRepository.listByDevRunning(1);
        if (runningList == null || runningList.isEmpty()) {
            log.info("[Keep Alive-daemon] warm-upcompleted: project");
            return 1;
        }

        log.info("[Keep Alive-daemon] startwarm-up, total: {}", runningList.size());
        int success = 0;
        for (CustomPageBuildModel model : runningList) {
            try {
                if (model.getProjectId() == null) {
                    continue;
                }
                updateKeepAliveCache(model.getProjectId(), model);
                success++;
            } catch (Exception e) {
                log.error("[Keep Alive-daemon] project Id={},warm-upfailed", model.getProjectId(), e);
            }
        }
        log.info("[Keep Alive-daemon] warm-upcompleted,total: {}, succeeded: {}", runningList.size(), success);
        return success;
    }

    /**
     * 定时检查保活超时
     */
    private void checkKeepAliveTimeout() {
        TenantFunctions.callWithIgnoreCheck(() -> {
            log.debug("[Keep Alive-daemon] scheduled checktimeoutproject");
            checkKeepAliveTimeout0();
            return null;
        });
    }

    /**
     * 定时检查保活超时
     */
    private void checkKeepAliveTimeout0() {
        // 使用分布式锁确保只有一个实例执行检查
        RLock lock = redissonClient.getLock(KEEPALIVE_LOCK_KEY);
        try {
            // 尝试获取锁，最多等待1秒，锁持有时间最多30秒
            if (lock.tryLock(1, 30, TimeUnit.SECONDS)) {
                try {
                    Date now = new Date();
                    long timeoutThreshold = now.getTime() - KEEPALIVE_TIMEOUT_MS;

                    //log.info("[Keep Alive-daemon] start timeoutproject, : {}", now);

                    // 从Redis获取所有保活项目ID
                    Set<Object> projectIds = redisUtil.members(KEEPALIVE_PROJECTS_SET_KEY);
                    if (projectIds == null || projectIds.isEmpty()) {
                        //log.debug("[Keep Alive-daemon] redis project,completed");
                        return;
                    }

                    log.info("[Keep Alive-daemon] found{}items to check", projectIds.size());

                    // 检查每个项目的保活状态
                    for (Object projectIdObj : projectIds) {
                        Long projectId = Long.valueOf(projectIdObj.toString());
                        //log.debug("[Keep Alive-daemon] start : project Id={} --", projectId);
                        try {
                            CustomPageBuildModel model = getKeepAliveCache(projectId);
                            if (model == null || model.getLastKeepAliveTime() == null) {
                                // 如果Redis中没有数据，从保活项目集合中移除
                                //log.debug("[Keep Alive-daemon] project Id={},redis , keep-alive", projectId);
                                redisUtil.remove(KEEPALIVE_PROJECTS_SET_KEY, projectId.toString());
                                continue;
                            }

                            long lastKeepAliveTime = model.getLastKeepAliveTime().getTime();
                            if (lastKeepAliveTime < timeoutThreshold) {
                                //log.debug("[Keep Alive-daemon] project Id={},projecttimeout, keep-alive : {},start executionstop service", projectId, model.getLastKeepAliveTime());

                                // 停止开发服务器
                                stopDevServerIfRunning(projectId, model);
                            }
                        } catch (Exception e) {
                            log.error("[Keep Alive-daemon] project Id={},keep-alive check exception", projectId, e);
                        }
                    }

                    //log.debug("[Keep Alive-daemon] keep-alive completed");
                } finally {
                    lock.unlock();
                }
            } else {
                log.info("[Keep Alive-daemon] failed to acquire distributed lock,skip");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Keep Alive-daemon] acquire distributed lock interrupted", e);
        } catch (Exception e) {
            log.error("[Keep Alive-daemon] keep-alive check exception", e);
        }
    }

    /**
     * 更新Redis中的保活缓存
     */
    private void updateKeepAliveCache(Long projectId, CustomPageBuildModel model) {
        try {
            String key = KEEPALIVE_KEY_PREFIX + projectId;
            String value = objectMapper.writeValueAsString(model);

            // 不设置过期时间，手动管理生命周期
            redisUtil.set(key, value);

            // 将projectId添加到保活项目集合中
            redisUtil.sSet(KEEPALIVE_PROJECTS_SET_KEY, projectId.toString());

            log.info("[Keep Alive-daemon] update Rediskeep-alivecache, project Id={}", projectId);
        } catch (JsonProcessingException e) {
            log.error("[Keep Alive-daemon] serialize keep-alive data failed, project Id={}", projectId, e);
        }
    }

    /**
     * 从Redis删除保活缓存
     */
    private void removeKeepAliveCache(Long projectId) {
        String key = KEEPALIVE_KEY_PREFIX + projectId;
        // 直接删除key，而不是设置过期时间
        redisUtil.expire(key, 0);

        // 从保活项目集合中移除projectId
        redisUtil.remove(KEEPALIVE_PROJECTS_SET_KEY, projectId.toString());

        log.info("[Keep Alive-daemon] project Id={},deleterediskeep-alivecachecompleted", projectId);
    }

    /**
     * 停止开发服务器（如果正在运行）
     */
    private void stopDevServerIfRunning(Long projectId, CustomPageBuildModel model) {
        boolean contextInstalled = false;
        try {
            if (model.getDevRunning() == null || model.getDevRunning() != 1 || model.getDevPid() == null) {
                removeKeepAliveCache(projectId);
                log.info("[Keep Alive-daemon] project Id={}, dev server not running, cleared keep-alive cache", projectId);
                return;
            }

            Long tenantId = resolveTenantId(projectId, model);
            if (tenantId == null) {
                log.error("[Keep Alive-daemon] project Id={}, cannot stop dev server: tenantId missing", projectId);
                return;
            }
            contextInstalled = installRequestContext(tenantId);

            log.info("[Keep Alive-daemon] project Id={}, pid={}, start stop dev service", projectId, model.getDevPid());
            Map<String, Object> resp = pageAppFileClient.stopDev(projectId, model.getDevPid());
            if (resp == null) {
                log.error("[Keep Alive-daemon] project Id={}, stop dev failed, no response", projectId);
                return;
            }
            boolean success = Boolean.parseBoolean(String.valueOf(resp.get("success")));
            String message = resp.get("message") == null ? "" : String.valueOf(resp.get("message"));
            if (!success) {
                log.error("[Keep Alive-daemon] project Id={}, stop dev failed, message={}", projectId, message);
                return;
            }

            removeKeepAliveCache(projectId);
            customPageBuildRepository.updateStopDevStatus(projectId, null);
            log.info("[Keep Alive-daemon] project Id={}, dev server stopped", projectId);
        } catch (Exception e) {
            log.error("[Keep Alive-daemon] project Id={} stop dev exception", projectId, e);
        } finally {
            if (contextInstalled) {
                RequestContext.remove();
            }
        }
    }

    private Long resolveTenantId(Long projectId, CustomPageBuildModel model) {
        if (model != null && model.getTenantId() != null) {
            return model.getTenantId();
        }
        CustomPageBuildModel dbModel = customPageBuildRepository.getByProjectId(projectId);
        return dbModel == null ? null : dbModel.getTenantId();
    }

    private boolean installRequestContext(Long tenantId) {
        RequestContext<?> existing = RequestContext.get();
        if (existing != null && tenantId.equals(existing.getTenantId())) {
            return false;
        }
        RequestContext<?> requestContext = new RequestContext<>();
        requestContext.setTenantId(tenantId);
        RequestContext.set(requestContext);
        return true;
    }

    /**
     * 从Redis获取保活缓存
     */
    private CustomPageBuildModel getKeepAliveCache(Long projectId) {
        try {
            String key = KEEPALIVE_KEY_PREFIX + projectId;
            Object value = redisUtil.get(key);
            if (value != null) {
                return objectMapper.readValue(value.toString(), CustomPageBuildModel.class);
            }
        } catch (JsonProcessingException e) {
            log.error("[Keep Alive-daemon] deserialize keep-alive data failed, project Id={}", projectId, e);
        }
        return null;
    }
}
