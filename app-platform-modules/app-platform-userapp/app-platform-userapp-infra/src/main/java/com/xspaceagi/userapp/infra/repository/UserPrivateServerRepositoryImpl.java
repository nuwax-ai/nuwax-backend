package com.xspaceagi.userapp.infra.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.domain.repository.IUserPrivateServerRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserPrivateServer;
import com.xspaceagi.userapp.infra.dao.service.IUserPrivateServerDaoService;
import com.xspaceagi.userapp.infra.translator.IUserPrivateServerTranslator;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

import java.util.Date;

import jakarta.annotation.Resource;

@Repository
public class UserPrivateServerRepositoryImpl implements IUserPrivateServerRepository {

    @Resource
    private IUserPrivateServerDaoService userPrivateServerDaoService;
    @Resource
    private IUserPrivateServerTranslator userPrivateServerTranslator;

    @Override
    public UserPrivateServerModel getById(Long id) {
        UserPrivateServer entity = userPrivateServerDaoService.getOne(Wrappers.<UserPrivateServer>lambdaQuery()
                .eq(UserPrivateServer::getId, id)
                .last("limit 1"));
        return userPrivateServerTranslator.convertToModel(entity);
    }

    @Override
    public List<UserPrivateServerModel> listByOwner(Long tenantId, Long ownerUserId) {
        List<UserPrivateServer> entities = userPrivateServerDaoService.list(
                Wrappers.<UserPrivateServer>lambdaQuery()
                        .eq(UserPrivateServer::getTenantId, tenantId)
                        .eq(UserPrivateServer::getOwnerUserId, ownerUserId)
                        .orderByDesc(UserPrivateServer::getId));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userPrivateServerTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public Long add(UserPrivateServerModel model) {
        UserPrivateServer entity = userPrivateServerTranslator.convertToEntity(model);
        userPrivateServerDaoService.save(entity);
        return entity.getId();
    }

    @Override
    public void updateById(UserPrivateServerModel model) {
        UserPrivateServer entity = userPrivateServerTranslator.convertToEntity(model);
        userPrivateServerDaoService.update(entity, Wrappers.<UserPrivateServer>lambdaUpdate()
                .eq(UserPrivateServer::getId, model.getId()));
    }

    @Override
    public void updateStatus(Long id, UserPrivateServerStatus status, Date lastHealthCheck) {
        // 显式 set 仅目标列：并发下的全实体写回会覆盖他人刚修改的字段
        userPrivateServerDaoService.update(Wrappers.<UserPrivateServer>lambdaUpdate()
                .set(UserPrivateServer::getStatus, status)
                .set(UserPrivateServer::getLastHealthCheck, lastHealthCheck)
                .eq(UserPrivateServer::getId, id));
    }

    @Override
    public void updateLastHealthCheck(Long id, Date lastHealthCheck) {
        // 仅刷时间列：状态未变时的探测回执，避免无谓写 status 触发乐观锁/行变更
        userPrivateServerDaoService.update(Wrappers.<UserPrivateServer>lambdaUpdate()
                .set(UserPrivateServer::getLastHealthCheck, lastHealthCheck)
                .eq(UserPrivateServer::getId, id));
    }

    @Override
    public void updateVersion(Long id, String version) {
        userPrivateServerDaoService.update(Wrappers.<UserPrivateServer>lambdaUpdate()
                .set(UserPrivateServer::getVersion, version)
                .eq(UserPrivateServer::getId, id));
    }

    @Override
    public void deleteById(Long id) {
        userPrivateServerDaoService.removeById(id);
    }
}
