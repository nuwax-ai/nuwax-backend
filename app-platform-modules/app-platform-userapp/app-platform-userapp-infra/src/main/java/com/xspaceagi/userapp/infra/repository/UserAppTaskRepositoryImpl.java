package com.xspaceagi.userapp.infra.repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.domain.repository.IUserAppTaskRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserAppTask;
import com.xspaceagi.userapp.infra.dao.service.IUserAppTaskService;
import com.xspaceagi.userapp.infra.translator.IUserAppTaskTranslator;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskStatus;

import jakarta.annotation.Resource;

@Repository
public class UserAppTaskRepositoryImpl implements IUserAppTaskRepository {

    /** error 列宽 varchar(1024) */
    private static final int ERROR_MAX_LENGTH = 1024;

    @Resource
    private IUserAppTaskService userAppTaskService;
    @Resource
    private IUserAppTaskTranslator userAppTaskTranslator;

    @Override
    public UserAppTaskModel getById(Long id) {
        UserAppTask entity = userAppTaskService.getOne(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getId, id)
                .last("limit 1"));
        return userAppTaskTranslator.convertToModel(entity);
    }

    @Override
    public UserAppTaskModel getByTaskId(String taskId) {
        UserAppTask entity = userAppTaskService.getOne(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getTaskId, taskId)
                .last("limit 1"));
        return userAppTaskTranslator.convertToModel(entity);
    }

    @Override
    public UserAppTaskModel findActiveByGuardKey(String guardKey) {
        UserAppTask entity = userAppTaskService.getOne(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getActiveGuard, guardKey)
                .last("limit 1"));
        return userAppTaskTranslator.convertToModel(entity);
    }

    @Override
    public List<UserAppTaskModel> findActiveByAppId(Long appId) {
        List<UserAppTask> entities = userAppTaskService.list(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getAppId, appId)
                .in(UserAppTask::getStatus, UserAppTaskStatus.PENDING.getValue(),
                        UserAppTaskStatus.RUNNING.getValue())
                .orderByDesc(UserAppTask::getId));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppTaskTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public UserAppTaskModel findLatestByAppId(Long appId, String taskType) {
        UserAppTask entity = userAppTaskService.getOne(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getAppId, appId)
                .eq(taskType != null, UserAppTask::getTaskType, taskType)
                .orderByDesc(UserAppTask::getId)
                .last("limit 1"));
        return userAppTaskTranslator.convertToModel(entity);
    }

    @Override
    public Long add(UserAppTaskModel model) {
        UserAppTask entity = userAppTaskTranslator.convertToEntity(model);
        try {
            userAppTaskService.save(entity);
        } catch (DuplicateKeyException e) {
            // uk_active_guard 冲突：同守卫组已有 pending/running 任务
            return null;
        }
        return entity.getId();
    }

    @Override
    public boolean updateAfterAccepted(Long id, String taskId, String status, String sandboxServerId) {
        // 仅活跃行可回填：行已被兜底置终态（如轮询宽限期超时）时保持终态不被覆盖
        LambdaUpdateWrapper<UserAppTask> wrapper = Wrappers.<UserAppTask>lambdaUpdate()
                .set(UserAppTask::getTaskId, taskId)
                .set(UserAppTask::getSandboxId, sandboxServerId);
        if (status != null) {
            wrapper.set(UserAppTask::getStatus, status);
        }
        return userAppTaskService.update(wrapper
                .eq(UserAppTask::getId, id)
                .in(UserAppTask::getStatus, UserAppTaskStatus.PENDING.getValue(),
                        UserAppTaskStatus.RUNNING.getValue()));
    }

    @Override
    public void updateGitCommit(Long id, String gitCommit) {
        userAppTaskService.update(Wrappers.<UserAppTask>lambdaUpdate()
                .set(UserAppTask::getGitCommit, gitCommit)
                .eq(UserAppTask::getId, id));
    }

    @Override
    public void updateStatusAndError(Long id, String status, String error) {
        userAppTaskService.update(Wrappers.<UserAppTask>lambdaUpdate()
                .set(UserAppTask::getStatus, status)
                .set(UserAppTask::getError, StringUtils.abbreviate(error, ERROR_MAX_LENGTH))
                .eq(UserAppTask::getId, id)
                .in(UserAppTask::getStatus, UserAppTaskStatus.PENDING.getValue(),
                        UserAppTaskStatus.RUNNING.getValue()));
    }

    @Override
    public boolean casToTerminal(Long id, String status, String error) {
        // 释放活跃槽位需置 NULL，必须走 wrapper 显式 set，禁止实体更新
        return userAppTaskService.update(Wrappers.<UserAppTask>lambdaUpdate()
                .set(UserAppTask::getStatus, status)
                .set(UserAppTask::getError, StringUtils.abbreviate(error, ERROR_MAX_LENGTH))
                .set(UserAppTask::getActiveGuard, null)
                .eq(UserAppTask::getId, id)
                .in(UserAppTask::getStatus, UserAppTaskStatus.PENDING.getValue(),
                        UserAppTaskStatus.RUNNING.getValue()));
    }

    @Override
    public void deleteByAppId(Long appId) {
        userAppTaskService.remove(Wrappers.<UserAppTask>lambdaQuery()
                .eq(UserAppTask::getAppId, appId));
    }

    @Override
    public List<UserAppTaskModel> findActiveOverdue(Date deadline) {
        // 系统维护任务跨租户扫描（与 app 预删除 sweep 同模式），后续单行写操作逐行恢复租户上下文
        List<UserAppTask> entities = TenantFunctions.callWithIgnoreCheck(() ->
                userAppTaskService.list(Wrappers.<UserAppTask>lambdaQuery()
                        .in(UserAppTask::getStatus, UserAppTaskStatus.PENDING.getValue(),
                                UserAppTaskStatus.RUNNING.getValue())
                        .lt(UserAppTask::getCreated, deadline)));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppTaskTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public List<UserAppTaskModel> findTerminalOverdue(Date deadline) {
        // 系统维护任务跨租户扫描
        List<UserAppTask> entities = TenantFunctions.callWithIgnoreCheck(() ->
                userAppTaskService.list(Wrappers.<UserAppTask>lambdaQuery()
                        .in(UserAppTask::getStatus, UserAppTaskStatus.COMPLETED.getValue(),
                                UserAppTaskStatus.FAILED.getValue(),
                                UserAppTaskStatus.CANCELLED.getValue())
                        .lt(UserAppTask::getCreated, deadline)));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppTaskTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        userAppTaskService.removeById(id);
    }
}
