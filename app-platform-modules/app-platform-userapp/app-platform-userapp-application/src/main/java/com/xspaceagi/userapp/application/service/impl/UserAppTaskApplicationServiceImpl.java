package com.xspaceagi.userapp.application.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import com.xspaceagi.userapp.application.schedule.UserAppTaskPollRunner;
import com.xspaceagi.userapp.application.service.IUserAppApplicationService;
import com.xspaceagi.userapp.application.service.IUserAppTaskApplicationService;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.gateway.UserAppDeployCommand;
import com.xspaceagi.userapp.domain.gateway.UserAppLogsQueryCommand;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.gateway.UserAppSseEvent;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.domain.service.IUserAppTaskDomainService;
import com.xspaceagi.userapp.domain.service.IUserAppDomainService;
import com.xspaceagi.userapp.domain.service.UserAppEndpointResolver;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;
import com.xspaceagi.userapp.sdk.dto.UserAppActiveTasksDto;
import com.xspaceagi.userapp.sdk.dto.UserAppCallResult;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskDto;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskStatus;
import com.xspaceagi.userapp.sdk.dto.UserAppStage;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskType;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * 编排流程无事务
 */
@Slf4j
@Service
public class UserAppTaskApplicationServiceImpl implements IUserAppTaskApplicationService {

    /** 构建产物归档兜底超时：超过后不再重试直接终结（须小于轮询上限 40 分钟保证可达；轮询末次另有强制终结兜底） */
    private static final long BUILD_ARTIFACT_TIMEOUT_MILLIS = 35 * 60_000L;

    /** 统一文件服务的业务类型标识 */
    private static final String FILE_TARGET_TYPE = "userapp_package";

    /** git commit 快照抓取专用：单线程串行，至多挂起一个线程，不占用请求线程 */
    private static final ExecutorService GIT_COMMIT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "userapp-git-commit-capture");
        t.setDaemon(true);
        return t;
    });

    /** 合成 error 事件载荷序列化 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private IUserAppTaskDomainService userAppTaskDomainService;
    @Resource
    private IUserAppDomainService userAppDomainService;
    @Resource
    private UserAppEndpointResolver userAppEndpointResolver;
    @Resource
    private IUserAppSandboxClient userAppSandboxClient;
    @Resource
    private UserAppTaskPollRunner userAppTaskPollRunner;
    @Resource
    private IUserAppApplicationService userAppApplicationService;
    @Resource
    private FileManagementService fileManagementService;
    @Resource
    private IFileAccessService iFileAccessService;
    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;
    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Override
    public UserAppTaskDto devStart(Long appId) {
        return submit(appId, UserAppTaskType.DEV_START);
    }

    @Override
    public UserAppTaskDto devRestart(Long appId) {
        return submit(appId, UserAppTaskType.DEV_RESTART);
    }

    @Override
    public void devStop(Long appId) {
        userAppTaskDomainService.devStop(appId, currentUserId());
    }

    @Override
    public UserAppTaskDto build(Long appId) {
        UserAppTaskDto task = submit(appId, UserAppTaskType.BUILD);
        // git commit 快照异步抓取回填：请求路径零额外 RPC（沙箱 git 端点无超时保障，挂起也不拖慢/阻断构建受理），
        // 抓取失败/挂起仅缺失快照字段，产物归档读任务行，无 commit 时版本记录该字段为空
        captureGitCommitAsync(appId, task.getId(), task.getTenantId());
        return task;
    }

    /** 异步抓取项目当前 commit 并回填任务行：恢复租户上下文供跨模块查询，任何失败只记日志 */
    private void captureGitCommitAsync(Long appId, Long rowId, Long tenantId) {
        GIT_COMMIT_EXECUTOR.execute(() -> {
            try {
                RequestContext.setThreadTenantId(tenantId);
                String gitCommit = captureProjectGitCommit(appId);
                if (StringUtils.isNotBlank(gitCommit)) {
                    userAppTaskDomainService.recordGitCommit(rowId, gitCommit);
                }
            } catch (Exception e) {
                log.warn("Capture userapp git commit failed, build continues without commit, appId={}, rowId={}",
                        appId, rowId, e);
            } finally {
                RequestContext.remove();
            }
        });
    }

    /** 按项目定位任一开发会话，取其沙箱工作区 git HEAD；无会话/git 不可用/调用失败返回 null */
    private String captureProjectGitCommit(Long appId) {
        List<ConversationDto> conversations = conversationApplicationService
                .getConversationListByDevTarget(ProjectTypeEnum.UserApp.name(), appId);
        if (conversations == null || conversations.isEmpty()) {
            return null;
        }
        ConversationDto conversation = conversations.get(0);
        return agentWorkspaceApplicationService.getGitCommitHash(conversation.getUserId(), conversation.getId());
    }

    @Override
    public void prodStart(Long appId, String releaseId) {
        prodAction(appId, "start", releaseId);
    }

    @Override
    public void prodStop(Long appId) {
        prodAction(appId, "stop", null);
    }

    @Override
    public void prodRestart(Long appId) {
        prodAction(appId, "restart", null);
    }

    @Override
    public boolean deployable(Long appId, String releaseId) {
        UserAppModel project = userAppApplicationService.getById(appId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        // releaseId 为空：以最新记录版本为准（与 prod/start 空 releaseId 的取数逻辑一致）
        String effectiveReleaseId = releaseId;
        if (StringUtils.isBlank(effectiveReleaseId)) {
            BuildVersionDto latest = project.latestBuildVersion();
            effectiveReleaseId = latest == null ? null : latest.getVersion();
        }
        // 无版本记录 / 版本号缺失 / 指定 releaseId 产物还在归档（未记录）均视为不可部署，前端轮询等待
        return StringUtils.isNotBlank(effectiveReleaseId) && project.findBuildVersion(effectiveReleaseId) != null;
    }

    @Override
    public Map<String, Object> queryLogs(Long appId, String appStage, UserAppLogsQueryCommand command) {
        appStage = normalizeAppStage(appStage);
        UserAppModel project = requireApp(appId);
        if (command == null) {
            command = UserAppLogsQueryCommand.builder().build();
        }
        // prod 跟随部署目标（私服部署查私服），dev 恒为项目沙箱
        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project,
                UserAppStage.PROD.getValue().equals(appStage));
        // user_id 与其他容器操作一致，取会话用户（当前访问者）
        UserAppCallResult result = userAppSandboxClient.queryLogs(handle, appId, currentUserId(),
                appStage, command);
        requireDownstreamSuccess(result);
        log.info("Userapp logs queried, appId={}, appStage={}", appId, appStage);
        return result.getData();
    }

    @Override
    public List<Map<String, Object>> queryLogSources(Long appId, String appStage) {
        appStage = normalizeAppStage(appStage);
        UserAppModel project = requireApp(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project,
                UserAppStage.PROD.getValue().equals(appStage));
        // user_id 取会话用户（当前访问者）
        UserAppCallResult result = userAppSandboxClient.queryLogSources(handle, appId, currentUserId(),
                appStage);
        requireDownstreamSuccess(result);
        log.info("Userapp log sources queried, appId={}, appStage={}", appId, appStage);
        // 下游成功但 data 缺失/非数组时 dataList 为 null，前端按数组消费，兜底空列表
        return result.getDataList() == null ? List.of() : result.getDataList();
    }

    @Override
    public Map<String, Object> queryReadiness(Long appId, String appStage) {
        appStage = normalizeAppStage(appStage);
        UserAppModel project = requireApp(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project,
                UserAppStage.PROD.getValue().equals(appStage));
        UserAppCallResult result = userAppSandboxClient.readiness(handle, appId, currentUserId(), appStage);
        requireDownstreamSuccess(result);
        return result.getData();
    }

    @Override
    public Map<String, Object> queryDbxReadiness(Long appId, String appStage) {
        appStage = normalizeAppStage(appStage);
        UserAppModel project = requireApp(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project,
                UserAppStage.PROD.getValue().equals(appStage));
        UserAppCallResult result = userAppSandboxClient.dbxReadiness(handle, appId, currentUserId(), appStage);
        requireDownstreamSuccess(result);
        return result.getData();
    }

    /** appStage 归一 */
    private static String normalizeAppStage(String appStage) {
        if (StringUtils.isBlank(appStage)) {
            return UserAppStage.DEV.getValue();
        }
        UserAppStage stage = UserAppStage.fromValue(appStage);
        if (stage == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非法的应用环境: " + appStage);
        }
        return stage.getValue();
    }

    /** 当前会话用户（fail-closed：无登录上下文直接报错，避免把 null 当字符串传给下游必填的 user_id） */
    private Long currentUserId() {
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (userId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "登录用户缺失，无法执行该操作");
        }
        return userId;
    }

    /** 应用存在性前置（日志查询/生产操作共用），不存在直接报错 */
    private UserAppModel requireApp(Long appId) {
        UserAppModel project = userAppApplicationService.getById(appId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        return project;
    }

    /**
     * 创建者的 userapp 闲置回收超时（秒）：取创建者数据权限（角色/用户组合并）。
     * 与数量配额同口径；-1/null 表示不限制（不下发，沿用下游默认），0 表示不回收（常驻）。
     * 取数失败仅告警并回落不限制：增强字段不阻断部署主流程。
     */
    private Integer creatorIdleTimeoutSeconds(UserAppModel project) {
        try {
            UserDataPermissionDto dataPermission = userDataPermissionRpcService.getUserDataPermission(project.getCreatorId());
            return dataPermission == null ? null : dataPermission.getUserAppIdleTimeoutSeconds();
        } catch (Exception e) {
            log.warn("Query creator data permission for idle timeout failed, fallback to unlimited, appId={}, creatorId={}",
                    project.getId(), project.getCreatorId(), e);
            return null;
        }
    }

    /** 下游 code≠0000 时错误原样透传给前端 */
    private void requireDownstreamSuccess(UserAppCallResult result) {
        if (!result.isSuccess()) {
            throw downstreamError(result, StringUtils.defaultIfBlank(result.getMessage(), "下游容器服务调用失败"));
        }
    }

    @Override
    public UserAppTaskDto cancel(String taskId) {
        return toDto(userAppTaskDomainService.cancel(taskId));
    }

    @Override
    public UserAppTaskDto getByTaskId(String taskId) {
        UserAppTaskModel model = userAppTaskDomainService.getByTaskId(taskId);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappTaskNotFound);
        }
        return toDto(model);
    }

    @Override
    public UserAppTaskDto getLatestByAppId(Long appId, UserAppTaskType taskType) {
        return toDto(userAppTaskDomainService.getLatestByAppId(appId, taskType));
    }

    @Override
    public UserAppActiveTasksDto getActiveTasks(Long appId) {
        requireApp(appId);
        List<UserAppTaskModel> actives = userAppTaskDomainService.findActiveByAppId(appId);
        // 守卫组互斥（dev：start/restart 同组；build 独立）：组内有活跃任务即不可再发起
        boolean devBusy = actives.stream().anyMatch(row ->
                UserAppTaskType.DEV_START.getValue().equals(row.getTaskType())
                        || UserAppTaskType.DEV_RESTART.getValue().equals(row.getTaskType()));
        boolean buildBusy = actives.stream().anyMatch(row ->
                UserAppTaskType.BUILD.getValue().equals(row.getTaskType()));
        UserAppActiveTasksDto dto = new UserAppActiveTasksDto();
        dto.setTasks(actives.stream().map(this::toDto).collect(Collectors.toList()));
        dto.setDevActionAllowed(!devBusy);
        dto.setBuildAllowed(!buildBusy);
        return dto;
    }

    @Override
    public void streamTaskLogs(UserAppTaskDto task, long fromSeq, SseEmitter emitter) {
        if (task == null || StringUtils.isBlank(task.getTaskId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappTaskNotAccepted);
        }
        // 任务行记录的受理服务器优先（属主校验用项目创建者，见 resolveTaskHandle），失效再按应用解析
        UserAppSandboxHandle handle = userAppTaskDomainService.resolveTaskHandle(task.getAppId(), task.getSandboxId());
        userAppSandboxClient.streamTaskLogs(handle, task.getAppId(), task.getTaskId(), task.getUserId(),
                Math.max(0L, fromSeq),
                event -> forwardSseEvent(emitter, event),
                () -> completeQuietly(emitter),
                message -> sendStreamError(emitter, message));
    }

    /** 下游事件原样透传：data 是预序列化 JSON，直接写字符串避免二次加引号 */
    private void forwardSseEvent(SseEmitter emitter, UserAppSseEvent event) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (StringUtils.isNotBlank(event.getEvent())) {
                builder.name(event.getEvent());
            }
            if (StringUtils.isNotBlank(event.getId())) {
                builder.id(event.getId());
            }
            // 多行 data 拆成多个 data: 字段行（SSE 多行的规范编码）——SseEmitter 原样写字符串，
            // 内嵌换行不拆分会破坏帧
            for (String segment : event.getData().split("\n", -1)) {
                builder.data(segment);
            }
            emitter.send(builder);
        } catch (Exception e) {
            // 前端已断开等发送失败：抛回网关中止下游订阅，避免无人消费的长连接
            throw new IllegalStateException("SSE emit failed: " + e.getMessage(), e);
        }
    }

    /** 前端连接已断时 complete 抛异常属预期，静默即可 */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Userapp task sse complete failed (client likely gone)", e);
        }
    }

    /** 下游连接失败/中断：EventSource 读不到非 200 响应体，统一以合成 error 事件收尾 */
    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("event", "error");
            payload.put("message", StringUtils.defaultIfBlank(message, "任务进度流中断"));
            emitter.send(SseEmitter.event().name("error").data(OBJECT_MAPPER.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("Userapp task sse send error event failed (client likely gone)", e);
        }
        completeQuietly(emitter);
    }

    /**
     * 提交 start/restart/build：守卫行（占用同守卫组槽位 active_guard，dev/build 各一槽）→
     * 注册轮询 → 下游受理 → 回填任务ID。
     * 任何一步失败：CAS 置 failed（释放活跃槽位）后把下游错误原样抛给前端。
     */
    private UserAppTaskDto submit(Long appId, UserAppTaskType taskType) {
        // 项目行只加载一次：任务行登记、端点解析与 dev 凭证取用共用（预删除应用在此即报 not found，不落任务行）
        UserAppModel project = requireApp(appId);
        UserAppTaskModel row = userAppTaskDomainService.submitTask(project, taskType, currentUserId());
        try {
            // 先注册轮询再调下游：进程崩溃时残留 pending 行由轮询兜底（60s 宽限后置 failed）
            userAppTaskPollRunner.startPoll(row.getId(), row.getTenantId());
            // 沙箱端点
            UserAppSandboxHandle handle = userAppEndpointResolver.resolveDev(project);

            UserAppCallResult result;
            if (taskType == UserAppTaskType.DEV_START || taskType == UserAppTaskType.DEV_RESTART) {
                // dev 启动/重启同生产：携带 pg 数据库账号密码下发下游（dev 凭证，键名与生产一致）
                UserAppDeployCommand command = UserAppDeployCommand.builder()
                        .userId(row.getUserId())
                        .dbUsername(project.getDevDbUsername())
                        .dbPassword(userAppApplicationService.getDbPassword(appId, true))
                        .build();
                result = taskType == UserAppTaskType.DEV_START
                        ? userAppSandboxClient.devStart(handle, appId, row.getUserId(), command)
                        : userAppSandboxClient.devRestart(handle, appId, row.getUserId(), command);
            } else {
                result = userAppSandboxClient.build(handle, appId, row.getUserId());
            }
            if (result.isSuccess() && StringUtils.isNotBlank(result.getDownstreamTaskId())) {
                userAppTaskDomainService.markAccepted(row.getId(), result.getDownstreamTaskId(),
                        result.getDownstreamStatus(), handle.getSandboxId());
                log.info("Userapp task accepted, rowId={}, type={}, taskId={}, status={}",
                        row.getId(), taskType.getValue(), result.getDownstreamTaskId(), result.getDownstreamStatus());
            } else {
                String message = StringUtils.defaultIfBlank(result.getMessage(), "下游未返回任务ID");
                userAppTaskDomainService.markFailed(row.getId(), message);
                throw downstreamError(result, message);
            }
        } catch (BizException e) {
            // resolve/受理失败已置 failed；这里兜住 markAccepted 之后不应发生但万一发生的业务异常
            userAppTaskDomainService.markFailed(row.getId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            userAppTaskDomainService.markFailed(row.getId(), e.getMessage());
            throw new BizException(e.getMessage() == null ? "下游容器服务调用失败" : e.getMessage());
        }
        return toDto(userAppTaskDomainService.getById(row.getId()));
    }

    /**
     * 生产部署/停止/重启（同步）。部署/重启成功后置生产部署标记；
     * 停止为纯启停（scale 0，无部署语义，不校验发布状态/版本与数据库凭证），不动部署标记——部署态与运行态解耦，能停止说明必然部署过。
     * 部署/重启流程：解析版本（指定 releaseId 优先，空则最新）→ 解密密码 → 转发下游。
     * 下游 code≠0000 时把错误原样抛给前端。
     * 部署目标：私服部署走属主私服（fail-closed 校验），平台部署走项目绑定沙箱；两者 agent 协议一致。
     */
    private void prodAction(Long appId, String action, String releaseId) {
        UserAppModel project = requireApp(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolveProd(project);
        // 下游 user_id 取会话用户（当前访问者），方法内只解析一次
        Long userId = currentUserId();
        if ("stop".equals(action)) {
            UserAppCallResult result = userAppSandboxClient.prodStop(handle, appId,
                    UserAppDeployCommand.builder().userId(userId).build());
            requireDownstreamSuccess(result);
            // 停止仅翻运行标记，不动部署标记（市场下架同样仅翻发布状态）；代理对停止状态返回停止页，重启即恢复
            userAppDomainService.markProdStopped(appId);
            log.info("Userapp prod stop done, appId={}", appId);
            return;
        }
        BuildVersionDto latest = resolveVersion(project, releaseId);
        latest.requireComplete();
//        if (StringUtils.isBlank(project.getProdDbUsername())) {
//            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProdDbCredentialMissing);
//        }
        String dbPassword = userAppApplicationService.getDbPassword(appId, false);
//        if (StringUtils.isBlank(dbPassword)) {
//            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProdDbCredentialMissing);
//        }
        String packageUrlWithAk = iFileAccessService.getFileUrlWithAk(latest.getPackageUrl(), true);
        UserAppDeployCommand command = UserAppDeployCommand.builder()
                .releaseId(latest.getVersion())
                .packageUrl(packageUrlWithAk)
                .dbUsername(project.getProdDbUsername())
                .dbPassword(dbPassword)
                .userId(userId)
                // 闲置回收超时取创建者数据权限（角色/用户组合并结果，与创建时数量配额同源）
                .idleTimeoutSeconds(creatorIdleTimeoutSeconds(project))
                .build();
        UserAppCallResult result = "restart".equals(action)
                ? userAppSandboxClient.prodRestart(handle, appId, command)
                : userAppSandboxClient.prodStart(handle, appId, command);
        requireDownstreamSuccess(result);
        // 部署成功即置已部署+运行中标记并记录部署版本（幂等）；发布上架以该版本为准
        userAppDomainService.markProdDeployed(appId, latest.getVersion());
        log.info("Userapp prod {} done, appId={}, releaseId={}", action, appId, latest.getVersion());
    }

    @Override
    public boolean processBuildArtifact(Long rowId) {
        UserAppTaskModel row = userAppTaskDomainService.getById(rowId);
        if (row == null) {
            return true;
        }
        UserAppTaskStatus status = UserAppTaskStatus.fromValue(row.getStatus());
        if (status != null && status.isTerminal()) {
            // 已被其他 tick 终结（并发/重试幂等保护）
            return true;
        }
        if (row.getCreated() != null && System.currentTimeMillis() - row.getCreated().getTime() > BUILD_ARTIFACT_TIMEOUT_MILLIS) {
            userAppTaskDomainService.finalizeBuild(rowId, "构建产物处理超时");
            return true;
        }
        try {
            UserAppSandboxHandle handle = userAppTaskDomainService.resolveTaskHandle(row.getAppId(), row.getSandboxId());
            UserAppCallResult task = userAppSandboxClient.getTask(handle, row.getAppId(), row.getTaskId(),
                    row.getUserId());
            if (task == null || !task.isSuccess()) {
                log.warn("Build artifact fetch task detail not success, rowId={}, taskId={}", rowId, row.getTaskId());
                return false;
            }
            String releaseId = task.getDownstreamReleaseId();
            if (StringUtils.isBlank(releaseId)) {
                log.warn("Build artifact releaseId missing, rowId={}, taskId={}", rowId, row.getTaskId());
                return false;
            }
            UserAppModel project = userAppApplicationService.getById(row.getAppId());
            if (project == null) {
                userAppTaskDomainService.finalizeBuild(rowId, "应用不存在");
                return true;
            }
            // 幂等：同 releaseId 版本已记录（并发 tick / 归档中途崩溃重试）则直接终结
            if (project.hasBuildVersion(releaseId)) {
                userAppTaskDomainService.finalizeBuild(rowId, null);
                return true;
            }
            byte[] artifact = userAppSandboxClient.downloadArtifact(handle, row.getAppId(), row.getUserId(),
                    releaseId);
            String fileName = StringUtils.defaultIfBlank(task.dataString("file_name"), "userapp-" + row.getAppId() + "-" + releaseId + ".zip");
            FileRecordDomain record = fileManagementService.uploadFile(
                    new InMemoryMultipartFile("file", fileName, "application/zip", artifact),
                    row.getTenantId(), row.getUserId(), FILE_TARGET_TYPE, row.getAppId(), releaseId, false);
            if (record == null || StringUtils.isBlank(record.getFileUrl())) {
                log.warn("Build artifact upload returned empty url, rowId={}, appId={}", rowId, row.getAppId());
                return false;
            }
            // git_commit 取构建发起时抓取并随任务行落库的快照（沙箱任务数据不回报该字段）
            userAppApplicationService.recordBuildVersion(row.getAppId(), releaseId, row.getGitCommit(),
                    record.getFileUrl(), UserContext.builder().userId(row.getUserId()).tenantId(row.getTenantId()).build());
            userAppTaskDomainService.finalizeBuild(rowId, null);
            log.info("Userapp build artifact archived, rowId={}, appId={}, releaseId={}, packageUrl={}",
                    rowId, row.getAppId(), releaseId, record.getFileUrl());
            return true;
        } catch (Exception e) {
            log.warn("Userapp build artifact process failed, retry next tick, rowId={}", rowId, e);
            return false;
        }
    }

    /** 解析部署版本：指定 releaseId 精确匹配（不存在报错），空则回退最新版本（latest中版本号最大者） */
    private BuildVersionDto resolveVersion(UserAppModel project, String releaseId) {
        if (StringUtils.isBlank(releaseId)) {
            return project.latestBuildVersion();
        }
        BuildVersionDto version = project.findBuildVersion(releaseId);
        if (version == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappBuildVersionNotFound);
        }
        return version;
    }

    /** 下游错误原样透传（code 缺失时用通用错误码兜底） */
    private BizException downstreamError(UserAppCallResult result, String message) {
        return new BizException(StringUtils.defaultIfBlank(result.getCode(), ErrorCodeEnum.ERROR_REQUEST.getCode()), message);
    }

    private UserAppTaskDto toDto(UserAppTaskModel model) {
        if (model == null) {
            return null;
        }
        UserAppTaskDto dto = new UserAppTaskDto();
        dto.setId(model.getId());
        dto.setTenantId(model.getTenantId());
        dto.setAppId(model.getAppId());
        dto.setUserId(model.getUserId());
        dto.setTaskType(model.getTaskType());
        dto.setTaskId(model.getTaskId());
        dto.setStatus(model.getStatus());
        dto.setError(model.getError());
        dto.setSandboxId(model.getSandboxId());
        dto.setGitCommit(model.getGitCommit());
        dto.setCreated(model.getCreated());
        dto.setModified(model.getModified());
        return dto;
    }
}
