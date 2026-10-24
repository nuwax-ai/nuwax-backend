package com.xspaceagi.userapp.domain.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.repository.IUserAppRepository;
import com.xspaceagi.userapp.domain.service.IUserAppDomainService;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserAppDomainServiceImpl implements IUserAppDomainService {

    @Resource
    private IUserAppRepository userAppRepository;

    @Override
    public UserAppModel create(UserAppModel model) {
        model.setPublishStatus(UserAppPublishStatus.Developing);
        Long id = userAppRepository.add(model);
        model.setId(id);
        return model;
    }

    @Override
    public UserAppModel update(UserAppModel model, UserContext userContext) {
        requireById(model.getId());
        userAppRepository.updateById(model);
        return userAppRepository.getById(model.getId());
    }

    @Override
    public void delete(Long id, UserContext userContext) {
        requireById(id);
        userAppRepository.deleteById(id);
    }

    @Override
    public UserAppModel getById(Long id) {
        return userAppRepository.getById(id);
    }

    @Override
    public long countByCreator(Long creatorId) {
        return userAppRepository.countByCreator(creatorId);
    }

    @Override
    public List<UserAppModel> list(UserAppModel query) {
        return userAppRepository.list(query);
    }

    @Override
    public SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize) {
        return userAppRepository.pageQuery(query, current, pageSize);
    }

    @Override
    public void recordBuildVersion(Long id, String version, String gitCommit, String packageUrl, UserContext userContext) {
        // 读-改-写追加版本记录，以 modified 做乐观锁：与任意行更新（如用户保存编辑）冲突时退避重试，
        // 避免无退避连败导致归档tick反复重传产物直至超时
        for (int attempt = 1; attempt <= 5; attempt++) {
            UserAppModel existing = requireById(id);
            if (existing.hasBuildVersion(version)) {
                //并发下已被记录（同releaseId），幂等返回
                return;
            }
            List<BuildVersionDto> versions = existing.getBuildVersions() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing.getBuildVersions());
            versions.forEach(v -> v.setLatest(false));
            versions.add(BuildVersionDto.builder()
                    .version(version)
                    .gitCommit(gitCommit)
                    .latest(true)
                    .packageUrl(packageUrl)
                    .buildTime(new Date())
                    .build());
            if (userAppRepository.updateBuildVersions(id, versions, existing.getModified())) {
                log.info("Userapp build version recorded, id={}, version={}, packageUrl={}", id, version, packageUrl);
                return;
            }
            log.warn("Userapp build version cas conflict, backoff retry, id={}, version={}, attempt={}/5", id, version, attempt);
            if (attempt < 5) {
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException(ErrorCodeEnum.ERROR_REQUEST.getCode(), "记录构建版本被中断");
                }
            }
        }
        throw new BizException(ErrorCodeEnum.ERROR_REQUEST.getCode(), "记录构建版本并发冲突，请重试");
    }

    @Override
    public void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext) {
        requireById(id);
        userAppRepository.updatePublishStatus(id, status);
    }

    @Override
    public void saveDbCredential(Long id, boolean devEnv, String username, String encryptedPassword, UserContext userContext) {
        UserAppModel existing = requireById(id);
        UserAppModel update = new UserAppModel();
        update.setId(id);
        if (devEnv) {
            update.setDevDbUsername(username);
            update.setDevDbPassword(encryptedPassword);
        } else {
            update.setProdDbUsername(username);
            update.setProdDbPassword(encryptedPassword);
        }
        userAppRepository.updateById(update);
    }

    @Override
    public void markProdDeployed(Long id, String releaseId) {
        requireById(id);
        UserAppModel update = new UserAppModel();
        update.setId(id);
        update.setProdDeployed(true);
        update.setProdRunning(true);
        update.setProdReleaseId(releaseId);
        userAppRepository.updateById(update);
    }

    @Override
    public void markProdStopped(Long id) {
        requireById(id);
        UserAppModel update = new UserAppModel();
        update.setId(id);
        update.setProdRunning(false);
        userAppRepository.updateById(update);
    }

    @Override
    public void updateDeployTarget(Long id, UserAppDeployType deployType, Long deployServerId) {
        requireById(id);
        userAppRepository.updateDeployTarget(id, deployType, deployServerId);
    }

    @Override
    public void markPreDelete(Long id, Date preDeleteTime) {
        requireById(id);
        userAppRepository.markPreDelete(id, preDeleteTime);
    }

    @Override
    public List<UserAppModel> listPreDeleteDue(Date deadline) {
        if (deadline == null) {
            return List.of();
        }
        return userAppRepository.listPreDeleteDue(deadline);
    }

    private UserAppModel requireById(Long id) {
        UserAppModel model = userAppRepository.getById(id);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        return model;
    }
}
