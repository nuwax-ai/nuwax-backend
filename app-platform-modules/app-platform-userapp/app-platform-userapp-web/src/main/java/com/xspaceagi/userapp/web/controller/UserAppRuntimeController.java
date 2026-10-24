package com.xspaceagi.userapp.web.controller;

import java.util.List;
import java.util.Map;

import com.xspaceagi.system.spec.annotation.RequireResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.userapp.application.service.IUserAppTaskApplicationService;
import com.xspaceagi.userapp.domain.gateway.UserAppLogsQueryCommand;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;
import com.xspaceagi.userapp.sdk.dto.UserAppActiveTasksDto;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskDto;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskType;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;
import com.xspaceagi.userapp.web.dto.UserAppActionReq;
import com.xspaceagi.userapp.web.dto.UserAppLogsQueryReq;
import com.xspaceagi.userapp.web.dto.UserAppLogsSourcesQueryReq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "User app", description = "User app runtime & task APIs")
@RestController
@RequestMapping("/api/userapp")
@Slf4j
public class UserAppRuntimeController extends BaseController {

    @Resource
    private IUserAppTaskApplicationService userAppTaskApplicationService;
    @Resource
    private IUserAppRpcService userAppRpcService;
    @Resource
    private SpacePermissionService spacePermissionService;

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "启动开发容器（异步任务，返回任务行；进行中重复发起将被拒绝）")
    @PostMapping("/dev/start")
    public ReqResult<UserAppTaskDto> devStart(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            return ReqResult.success(userAppTaskApplicationService.devStart(req.getAppId()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Start userapp dev container failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "重启开发容器（异步任务，返回任务行；进行中重复发起将被拒绝）")
    @PostMapping("/dev/restart")
    public ReqResult<UserAppTaskDto> devRestart(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            return ReqResult.success(userAppTaskApplicationService.devRestart(req.getAppId()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Restart userapp dev container failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "停止开发容器")
    @PostMapping("/dev/stop")
    public ReqResult<Void> devStop(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            userAppTaskApplicationService.devStop(req.getAppId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Stop userapp dev container failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DEPLOY)
    @Operation(summary = "构建打包（异步任务；完成后自动上传产物到文件服务并记录发布版本）")
    @PostMapping("/build")
    public ReqResult<UserAppTaskDto> build(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            return ReqResult.success(userAppTaskApplicationService.build(req.getAppId()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Build userapp failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询应用构建包版本列表")
    @GetMapping("/build-versions")
    public ReqResult<List<BuildVersionDto>> buildVersions(@RequestParam("appId") Long appId) {
        try {
            getUser();
            if (appId == null) {
                throw new IllegalArgumentException("appId is required");
            }
            UserAppDto app = requireApp(appId);
            return ReqResult.success(app.getBuildVersions() == null ? List.of() : app.getBuildVersions());
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp build versions failed, appId={}", appId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DEPLOY)
    @Operation(summary = "生产部署（构建完成即可部署，无需先发布上架；发布上架要求先完成生产部署）")
    @PostMapping("/prod/start")
    public ReqResult<Void> prodStart(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            userAppTaskApplicationService.prodStart(req.getAppId(), req.getReleaseId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Start userapp prod failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DEPLOY)
    @Operation(summary = "版本是否可生产部署（releaseId 为空查最新版本就绪状态；前端轮询至 true 即可调 prod/start）")
    @GetMapping("/prod/deployable")
    public ReqResult<Boolean> deployable(@RequestParam("appId") Long appId,
            @RequestParam(value = "releaseId", required = false) String releaseId) {
        try {
            getUser();
            if (appId == null) {
                throw new IllegalArgumentException("appId is required");
            }
            checkSpacePermissionByProjectId(appId);
            // 判定逻辑（含空 releaseId 解析）统一在 application 层
            return ReqResult.success(userAppTaskApplicationService.deployable(appId, releaseId));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp deployable failed, appId={}, releaseId={}", appId, releaseId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DEPLOY)
    @Operation(summary = "生产停止（停止后清除生产部署标记，重新发布上架前需再次部署）")
    @PostMapping("/prod/stop")
    public ReqResult<Void> prodStop(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            userAppTaskApplicationService.prodStop(req.getAppId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Stop userapp prod failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_DEPLOY)
    @Operation(summary = "生产重启")
    @PostMapping("/prod/restart")
    public ReqResult<Void> prodRestart(@RequestBody UserAppActionReq req) {
        try {
            getUser();
            requireAppId(req);
            checkSpacePermissionByProjectId(req.getAppId());
            userAppTaskApplicationService.prodRestart(req.getAppId());
            return ReqResult.success();
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Restart userapp prod failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询应用日志源")
    @PostMapping("/logs/sources/query")
    public ReqResult<List<Map<String, Object>>> queryLogSources(@RequestBody UserAppLogsSourcesQueryReq req) {
        try {
            getUser();
            if (req == null || req.getAppId() == null) {
                throw new IllegalArgumentException("appId is required");
            }
            checkSpacePermissionByProjectId(req.getAppId());
            // env 归一与校验在服务层
            return ReqResult.success(userAppTaskApplicationService.queryLogSources(req.getAppId(), req.getEnv()));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp log sources failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "应用就绪探测")
    @GetMapping("/readiness")
    public ReqResult<Map<String, Object>> readiness(@RequestParam("appId") Long appId,
            @RequestParam(value = "env", required = false) String env) {
        try {
            getUser();
            if (appId == null) {
                throw new IllegalArgumentException("appId is required");
            }
            checkSpacePermissionByProjectId(appId);
            // env 归一与校验在服务层
            return ReqResult.success(userAppTaskApplicationService.queryReadiness(appId, env));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp readiness failed, appId={}, env={}", appId, env, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "dbx 数据库就绪探测")
    @GetMapping("/dbx/readiness")
    public ReqResult<Map<String, Object>> dbxReadiness(@RequestParam("appId") Long appId,
            @RequestParam(value = "env", required = false) String env) {
        try {
            getUser();
            if (appId == null) {
                throw new IllegalArgumentException("appId is required");
            }
            checkSpacePermissionByProjectId(appId);
            // env 归一与校验在服务层
            return ReqResult.success(userAppTaskApplicationService.queryDbxReadiness(appId, env));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp dbx readiness failed, appId={}, env={}", appId, env, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询应用日志")
    @PostMapping("/logs/query")
    public ReqResult<Map<String, Object>> queryLogs(@RequestBody UserAppLogsQueryReq req) {
        try {
            getUser();
            if (req == null || req.getAppId() == null) {
                throw new IllegalArgumentException("appId is required");
            }
            checkSpacePermissionByProjectId(req.getAppId());
            UserAppLogsQueryCommand command = UserAppLogsQueryCommand.builder()
                    .cursor(req.getCursor())
                    .keyword(req.getKeyword())
                    .levels(req.getLevels())
                    .selectors(toCommandSelectors(req.getSelectors()))
                    .since(req.getSince())
                    .tail(req.getTail())
                    .until(req.getUntil())
                    .build();
            return ReqResult.success(userAppTaskApplicationService.queryLogs(req.getAppId(), req.getEnv(), command));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp logs failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    private List<UserAppLogsQueryCommand.LogSelector> toCommandSelectors(List<UserAppLogsQueryReq.Selector> selectors) {
        if (selectors == null) {
            return null;
        }
        return selectors.stream()
                .map(s -> UserAppLogsQueryCommand.LogSelector.builder()
                        .serviceId(s.getServiceId())
                        .sourceIds(s.getSourceIds())
                        .build())
                .toList();
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "取消任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public ReqResult<UserAppTaskDto> cancelTask(@PathVariable("taskId") String taskId) {
        try {
            getUser();
            UserAppTaskDto task = userAppTaskApplicationService.getByTaskId(taskId);
            checkSpacePermissionByProjectId(task.getAppId());
            return ReqResult.success(userAppTaskApplicationService.cancel(taskId));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Cancel userapp dev task failed, taskId={}", taskId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "按任务ID查询任务状态（读库，新鲜度由后台轮询保证）")
    @GetMapping("/tasks/{taskId}")
    public ReqResult<UserAppTaskDto> getTask(@PathVariable("taskId") String taskId) {
        try {
            getUser();
            UserAppTaskDto task = userAppTaskApplicationService.getByTaskId(taskId);
            checkSpacePermissionByProjectId(task.getAppId());
            return ReqResult.success(task);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query userapp dev task failed, taskId={}", taskId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询应用最近一条任务（无任务返回 null；taskType：dev_start/dev_restart/build）")
    @GetMapping("/tasks/latest")
    public ReqResult<UserAppTaskDto> getLatestTask(@RequestParam("appId") Long appId,
            @RequestParam("taskType") String taskType) {
        try {
            getUser();
            checkSpacePermissionByProjectId(appId);
            return ReqResult.success(
                    userAppTaskApplicationService.getLatestByAppId(appId, requireTaskType(taskType)));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query latest userapp task failed, appId={}, taskType={}", appId, taskType, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "查询应用进行中任务与操作可用性（tasks：为进行中的任务；devActionAllowed/buildAllowed：标志可否发起）")
    @GetMapping("/tasks/active")
    public ReqResult<UserAppActiveTasksDto> getActiveTasks(@RequestParam("appId") Long appId) {
        try {
            getUser();
            checkSpacePermissionByProjectId(appId);
            return ReqResult.success(userAppTaskApplicationService.getActiveTasks(appId));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Query active userapp tasks failed, appId={}", appId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @RequireResource(USER_APP_QUERY)
    @Operation(summary = "任务进度 SSE（dev/start、dev/restart、build 共用）")
    @GetMapping(value = "/tasks/{taskId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskLogs(@PathVariable("taskId") String taskId,
            @RequestParam(value = "fromSeq", defaultValue = "0") long fromSeq,
            HttpServletResponse response) {
        // SSE 不走代理缓冲；no-timeout 由消费端断连感知
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
        SseEmitter emitter = new SseEmitter(-1L);
        try {
            getUser();
            UserAppTaskDto task = userAppTaskApplicationService.getByTaskId(taskId);
            checkSpacePermissionByProjectId(task.getAppId());
            userAppTaskApplicationService.streamTaskLogs(task, fromSeq, emitter);
        } catch (Exception e) {
            log.error("Stream userapp task logs failed, taskId={}, fromSeq={}", taskId, fromSeq, e);
            sendStreamErrorAndComplete(emitter, e.getMessage());
        }
        return emitter;
    }

    /** 建流前失败（鉴权/权限/任务未受理）：EventSource 读不到非 200 响应体，统一 200 + error 事件 */
    private void sendStreamErrorAndComplete(SseEmitter emitter, String message) {
        try {
            // 手拼 JSON 需转义反斜杠/引号/换行，其余控制字符（如 \t）JSON 字符串不允许裸写，替换为空格
            String safe = (message == null ? "" : message)
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "").replace("\n", "\\n")
                    .replaceAll("[\\x00-\\x1f]", " ");
            emitter.send(SseEmitter.event().name("error")
                    .data("{\"event\":\"error\",\"message\":\"" + safe + "\"}"));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    /** taskType 传存储值（dev_start/dev_restart/build，大小写不敏感） */
    private UserAppTaskType requireTaskType(String taskType) {
        UserAppTaskType type = UserAppTaskType.fromValue(taskType);
        if (type == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非法的任务类型: " + taskType);
        }
        return type;
    }

    private void requireAppId(UserAppActionReq req) {
        if (req == null || req.getAppId() == null) {
            throw new IllegalArgumentException("appId is required");
        }
    }

    /**
     * 空间权限校验。
     */
    private void checkSpacePermissionByProjectId(Long id) {
        requireApp(id);
    }

    /** 取应用行并校验空间权限（应用不存在直接报错） */
    private UserAppDto requireApp(Long id) {
        UserAppDto dto = userAppRpcService.queryById(id);
        if (dto != null) {
            spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
        } else {
            throw new IllegalArgumentException("User app does not exist");
        }
        return dto;
    }
}
