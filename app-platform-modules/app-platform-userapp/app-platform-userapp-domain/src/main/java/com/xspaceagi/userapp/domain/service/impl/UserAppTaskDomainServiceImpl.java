package com.xspaceagi.userapp.domain.service.impl;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.repository.IUserAppTaskRepository;
import com.xspaceagi.userapp.domain.repository.IUserAppRepository;
import com.xspaceagi.userapp.domain.service.UserAppEndpointResolver;
import com.xspaceagi.userapp.domain.service.IUserAppTaskDomainService;
import com.xspaceagi.userapp.domain.service.UserAppTaskPollOutcome;
import com.xspaceagi.userapp.sdk.dto.UserAppCallResult;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskStatus;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskType;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserAppTaskDomainServiceImpl implements IUserAppTaskDomainService {

    /** task_id 回填前的宽限期：超过仍未回填视为受理失败 */
    private static final long ACCEPT_GRACE_MILLIS = 60_000L;

    @Resource
    private IUserAppTaskRepository userAppTaskRepository;
    @Resource
    private IUserAppRepository userAppRepository;
    @Resource
    private IUserAppSandboxClient userAppSandboxClient;
    @Resource
    private UserAppEndpointResolver userAppEndpointResolver;

    @Override
    public UserAppTaskModel submitTask(UserAppModel project, UserAppTaskType taskType, Long userId) {
        Long appId = project.getId();
        UserAppTaskModel model = new UserAppTaskModel();
        model.setTenantId(project.getTenantId());
        model.setAppId(appId);
        //任务行记录提交者（会话用户）：下游 userId 一律传会话/操作者，异步归档沿用此值
        model.setUserId(userId);
        model.setTaskType(taskType.getValue());
        model.setStatus(UserAppTaskStatus.PENDING.getValue());
        model.setActiveGuard(taskType.guardKey(appId));
        Long id = userAppTaskRepository.add(model);
        if (id == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappTaskInProgress);
        }
        model.setId(id);
        return model;
    }

    @Override
    public void markAccepted(Long id, String taskId, String downstreamStatus, String sandboxId) {
        UserAppTaskStatus status = UserAppTaskStatus.fromValue(downstreamStatus);
        boolean applied = userAppTaskRepository.updateAfterAccepted(id, taskId,
                status == null ? null : status.getValue(), sandboxId);
        if (!applied) {
            // 行已被并发置终态（取消/兜底超时）：下游任务实际在跑但平台行不会更新
            log.warn("Userapp task accept-backfill skipped, row already terminal, id={}, taskId={}", id, taskId);
        }
    }

    @Override
    public void recordGitCommit(Long id, String gitCommit) {
        if (id == null || StringUtils.isBlank(gitCommit)) {
            return;
        }
        userAppTaskRepository.updateGitCommit(id, gitCommit);
    }

    @Override
    public void markFailed(Long id, String error) {
        userAppTaskRepository.casToTerminal(id, UserAppTaskStatus.FAILED.getValue(), error);
    }

    @Override
    public void devStop(Long appId, Long userId) {
        // 不检查进行中任务：dev/start、dev/restart 进行中允许停止容器
        UserAppModel project = requireProject(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolveDev(project);
        // 下游 user_id 取会话用户（与 dev/start 的容器登记人一致）
        UserAppCallResult result = userAppSandboxClient.devStop(handle, appId, userId);
        if (!result.isSuccess()) {
            throw downstreamError(result);
        }
        // 容器已停，进行中的 dev 任务（start/restart 同守卫组，至多一条）不可能再成功：
        // 立即置 CANCELLED 释放守卫槽位。
        UserAppTaskModel active = userAppTaskRepository.findActiveByGuardKey(
                UserAppTaskType.DEV_START.guardKey(appId));
        if (active != null) {
            userAppTaskRepository.casToTerminal(active.getId(), UserAppTaskStatus.CANCELLED.getValue(),
                    "开发容器已停止");
            log.info("Userapp dev task cancelled by container stop, appId={}, rowId={}, taskId={}",
                    appId, active.getId(), active.getTaskId());
        }
        log.info("Userapp dev container stopped, appId={}, userId={}", appId, project.getCreatorId());
    }

    @Override
    public UserAppTaskModel cancel(String taskId) {
        UserAppTaskModel row = userAppTaskRepository.getByTaskId(taskId);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappTaskNotFound);
        }
        UserAppTaskStatus status = UserAppTaskStatus.fromValue(row.getStatus());
        if (status != null && status.isTerminal()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappTaskNotCancellable);
        }
        if (StringUtils.isBlank(row.getTaskId())) {
            // 受理前残留（进程崩溃等）：无下游任务可取消，直接置 cancelled
            userAppTaskRepository.casToTerminal(row.getId(), UserAppTaskStatus.CANCELLED.getValue(), null);
        } else {
            UserAppSandboxHandle handle = resolveTaskHandle(row.getAppId(), row.getSandboxId());
            UserAppCallResult result = userAppSandboxClient.cancelTask(handle, row.getAppId(), row.getTaskId(),
                    row.getUserId());
            if (!result.isSuccess()) {
                throw downstreamError(result);
            }
            userAppTaskRepository.casToTerminal(row.getId(), UserAppTaskStatus.CANCELLED.getValue(), null);
        }
        log.info("Userapp dev task cancelled, rowId={}, taskId={}", row.getId(), taskId);
        return userAppTaskRepository.getById(row.getId());
    }

    @Override
    public UserAppTaskModel getById(Long id) {
        return userAppTaskRepository.getById(id);
    }

    @Override
    public UserAppSandboxHandle resolveTaskHandle(Long appId, String sandboxId) {
        // 沙箱属主校验用项目创建者（沙箱绑定在创建者名下；任务行 userId 是提交者，语义不同），
        // 校验失败回退按应用解析；下游调用的 user_id 仍由调用方用提交者传
        UserAppModel project = requireProject(appId);
        UserAppSandboxHandle handle = userAppEndpointResolver.resolveBySandboxId(sandboxId, project.getCreatorId());
        if (handle != null) {
            return handle;
        }
        return userAppEndpointResolver.resolveDev(project);
    }

    @Override
    public UserAppTaskModel getByTaskId(String taskId) {
        return userAppTaskRepository.getByTaskId(taskId);
    }

    @Override
    public UserAppTaskModel getLatestByAppId(Long appId, UserAppTaskType taskType) {
        return userAppTaskRepository.findLatestByAppId(appId, taskType == null ? null : taskType.getValue());
    }

    @Override
    public List<UserAppTaskModel> findActiveByAppId(Long appId) {
        return userAppTaskRepository.findActiveByAppId(appId);
    }

    @Override
    public UserAppTaskPollOutcome pollOnce(Long rowId, int execTimes, int maxExecTimes) {
        UserAppTaskModel row = userAppTaskRepository.getById(rowId);
        if (row == null) {
            return UserAppTaskPollOutcome.finished();
        }
        UserAppTaskStatus status = UserAppTaskStatus.fromValue(row.getStatus());
        if (status != null && status.isTerminal()) {
            return UserAppTaskPollOutcome.finished();
        }
        if (StringUtils.isBlank(row.getTaskId())) {
            // 尚未受理：给受理流程留宽限期，超时仍未回填则判失败
            if (row.getCreated() != null && System.currentTimeMillis() - row.getCreated().getTime() < ACCEPT_GRACE_MILLIS) {
                return UserAppTaskPollOutcome.continuePolling();
            }
            userAppTaskRepository.casToTerminal(row.getId(), UserAppTaskStatus.FAILED.getValue(), "downstream task id missing");
            return UserAppTaskPollOutcome.finished();
        }

        UserAppCallResult result;
        try {
            UserAppSandboxHandle handle = resolveTaskHandle(row.getAppId(), row.getSandboxId());
            result = userAppSandboxClient.getTask(handle, row.getAppId(), row.getTaskId(), row.getUserId());
        } catch (Exception e) {
            log.warn("Userapp task poll resolve/query failed, rowId={}, taskId={}", rowId, row.getTaskId(), e);
            return checkTimeout(row, execTimes, maxExecTimes);
        }
        if (result == null || !result.isSuccess()) {
            log.warn("Userapp task poll query not success, rowId={}, taskId={}, code={}, message={}",
                    rowId, row.getTaskId(), result == null ? null : result.getCode(),
                    result == null ? null : result.getMessage());
            return checkTimeout(row, execTimes, maxExecTimes);
        }
        UserAppTaskStatus downstream = UserAppTaskStatus.fromValue(result.getDownstreamStatus());
        if (downstream == null) {
            log.warn("Userapp task poll got unknown status, rowId={}, taskId={}, status={}",
                    rowId, row.getTaskId(), result.getDownstreamStatus());
            return checkTimeout(row, execTimes, maxExecTimes);
        }
        if (downstream.isTerminal()) {
            if (downstream == UserAppTaskStatus.COMPLETED && isBuildTask(row)) {
                // 构建完成：交由应用层归档产物（下载/上传/记版本）后 finalizeBuild 终结，行保持 running 供重试与幂等判断；
                // 归档超时兜底在应用层（BUILD_ARTIFACT_TIMEOUT_MILLIS）与轮询末次强制 finalize 双重保证，
                // 不在此处按轮询次数判死，避免把"归档未完成"误报成"任务状态轮询超时"
                return UserAppTaskPollOutcome.buildArtifactReady();
            }
            userAppTaskRepository.casToTerminal(row.getId(), downstream.getValue(),
                    UserAppTaskStatus.FAILED == downstream ? result.getDownstreamError() : null);
            return UserAppTaskPollOutcome.finished();
        }
        userAppTaskRepository.updateStatusAndError(row.getId(), downstream.getValue(), result.getDownstreamError());
        return checkTimeout(row, execTimes, maxExecTimes);
    }

    @Override
    public void finalizeBuild(Long id, String error) {
        if (StringUtils.isBlank(error)) {
            userAppTaskRepository.casToTerminal(id, UserAppTaskStatus.COMPLETED.getValue(), null);
        } else {
            userAppTaskRepository.casToTerminal(id, UserAppTaskStatus.FAILED.getValue(), error);
        }
    }

    private boolean isBuildTask(UserAppTaskModel row) {
        return UserAppTaskType.fromValue(row.getTaskType()) == UserAppTaskType.BUILD;
    }

    /** 超时兜底：达到轮询上限且仍活跃时置 failed；execTimes 为调度 DTO 携带的本次 tick 前值 */
    private UserAppTaskPollOutcome checkTimeout(UserAppTaskModel row, int execTimes, int maxExecTimes) {
        if (execTimes + 1 < maxExecTimes) {
            return UserAppTaskPollOutcome.continuePolling();
        }
        UserAppTaskModel latest = userAppTaskRepository.getById(row.getId());
        UserAppTaskStatus status = latest == null ? null : UserAppTaskStatus.fromValue(latest.getStatus());
        if (status != null && status.isTerminal()) {
            return UserAppTaskPollOutcome.finished();
        }
        userAppTaskRepository.casToTerminal(row.getId(), UserAppTaskStatus.FAILED.getValue(), "任务状态轮询超时");
        return UserAppTaskPollOutcome.finished();
    }

    /** 下游错误原样透传（code/message 缺失时用通用错误码兜底） */
    private BizException downstreamError(UserAppCallResult result) {
        return new BizException(
                StringUtils.defaultIfBlank(result.getCode(), ErrorCodeEnum.ERROR_REQUEST.getCode()),
                StringUtils.defaultIfBlank(result.getMessage(), ErrorCodeEnum.ERROR_REQUEST.getMsg()));
    }

    private UserAppModel requireProject(Long appId) {
        UserAppModel project = userAppRepository.getById(appId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        return project;
    }

    @Override
    public void deleteByAppId(Long appId) {
        userAppTaskRepository.deleteByAppId(appId);
        log.info("Userapp tasks removed for app purge, appId={}", appId);
    }

    @Override
    public List<UserAppTaskModel> findOverdueActive(Date deadline) {
        return userAppTaskRepository.findActiveOverdue(deadline);
    }

    @Override
    public List<UserAppTaskModel> findOverdueTerminal(Date deadline) {
        return userAppTaskRepository.findTerminalOverdue(deadline);
    }

    @Override
    public void deleteById(Long id) {
        userAppTaskRepository.deleteById(id);
    }
}
