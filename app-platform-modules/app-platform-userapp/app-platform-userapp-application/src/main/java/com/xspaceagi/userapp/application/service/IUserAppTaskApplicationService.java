package com.xspaceagi.userapp.application.service;

import java.util.List;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.xspaceagi.userapp.domain.gateway.UserAppLogsQueryCommand;
import com.xspaceagi.userapp.sdk.dto.UserAppActiveTasksDto;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskDto;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskType;

public interface IUserAppTaskApplicationService {

    UserAppTaskDto devStart(Long appId);

    UserAppTaskDto devRestart(Long appId);

    void devStop(Long appId);

    UserAppTaskDto build(Long appId);

    void prodStart(Long appId, String releaseId);

    void prodStop(Long appId);

    void prodRestart(Long appId);

    /**
     * 查询应用日志（appStage：dev/prod），下游 data 原样返回给前端。
     */
    Map<String, Object> queryLogs(Long appId, String appStage, UserAppLogsQueryCommand command);

    /**
     * 指定版本是否已可生产部署
     */
    boolean deployable(Long appId, String releaseId);

    /**
     * 查询应用日志源
     */
    List<Map<String, Object>> queryLogSources(Long appId, String appStage);

    /**
     * 应用就绪探测
     */
    Map<String, Object> queryReadiness(Long appId, String appStage);

    /**
     * dbx 数据库就绪探测
     */
    Map<String, Object> queryDbxReadiness(Long appId, String appStage);

    /**
     * BUILD 任务产物归档（下载 zip → 上传文件服务 → 记发布版本 → 终结任务）。
     *
     * @return false 表示本轮处理失败，任务行保持 running 由下轮重试
     */
    boolean processBuildArtifact(Long rowId);

    UserAppTaskDto cancel(String taskId);

    UserAppTaskDto getByTaskId(String taskId);

    UserAppTaskDto getLatestByAppId(Long appId, UserAppTaskType taskType);

    /** 应用进行中任务与操作可用性 */
    UserAppActiveTasksDto getActiveTasks(Long appId);

    /**
     * 订阅任务进度 SSE ；下游正常关流后 complete；
     * 下游连接失败/中断时发合成 error 事件（event:error，data 含 message）后 complete。
     */
    void streamTaskLogs(UserAppTaskDto task, long fromSeq, SseEmitter emitter);
}
