package com.xspaceagi.userapp.domain.gateway;

import java.util.function.Consumer;

import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.UserAppCallResult;

public interface IUserAppSandboxClient {

    /** 沙箱解析（端点寻址统一走 UserAppEndpointResolver；沙箱以项目绑定为准，不做回退） */
    UserAppSandboxHandle resolveByProject(UserAppModel project);

    /** 云端沙箱分配 */
    Long allocateCloudSandbox(Long tenantId, Long userId);

    /** 按沙箱ID解析配置（属主校验在 agent 模块 SandboxConfigEndpoints）：配置不存在/属主不匹配/不可用返回 null，由调用方决定抛错或回退 */
    UserAppSandboxHandle resolveBySandboxId(Long sandboxId, Long expectedUserId);

    UserAppCallResult devStart(UserAppSandboxHandle handle, Long appId, Long userId, UserAppDeployCommand command);

    UserAppCallResult devStop(UserAppSandboxHandle handle, Long appId, Long userId);

    UserAppCallResult devRestart(UserAppSandboxHandle handle, Long appId, Long userId, UserAppDeployCommand command);

    UserAppCallResult build(UserAppSandboxHandle handle, Long appId, Long userId);

    UserAppCallResult prodStart(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command);

    UserAppCallResult prodStop(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command);

    UserAppCallResult prodRestart(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command);

    /** 重置数据库账号密码 */
    UserAppCallResult resetDbPassword(UserAppSandboxHandle handle, Long appId, Long userId, String appStage,
            String username, String password);

    /** 应用日志查询（appStage 区分 dev/prod，下游 data 原样透传） */
    UserAppCallResult queryLogs(UserAppSandboxHandle handle, Long appId, Long userId, String appStage,
            UserAppLogsQueryCommand command);

    /** 应用日志源查询 */
    UserAppCallResult queryLogSources(UserAppSandboxHandle handle, Long appId, Long userId, String appStage);

    /** 应用就绪探测 */
    UserAppCallResult readiness(UserAppSandboxHandle handle, Long appId, Long userId, String appStage);

    /** dbx 数据库就绪探测 */
    UserAppCallResult dbxReadiness(UserAppSandboxHandle handle, Long appId, Long userId, String appStage);

    byte[] downloadArtifact(UserAppSandboxHandle handle, Long appId, Long userId, String releaseId);

    UserAppCallResult cancelTask(UserAppSandboxHandle handle, Long appId, String taskId, Long userId);

    UserAppCallResult getTask(UserAppSandboxHandle handle, Long appId, String taskId, Long userId);

    /**
     * 订阅任务进度 SSE（异步，回调在网关消费线程上执行）：
     * onEvent 每条下游事件回调一次（回调抛异常视为消费端故障，中止下游订阅且不回调 onFinished/onError）；
     * onFinished 在下游正常关流（终态事件后）回调；onError 在连接失败/流中断时回调。
     */
    void streamTaskLogs(UserAppSandboxHandle handle, Long appId, String taskId, Long userId, long fromSeq,
            Consumer<UserAppSseEvent> onEvent, Runnable onFinished, Consumer<String> onError);
}
