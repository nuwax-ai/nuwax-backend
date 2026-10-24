package com.xspaceagi.userapp.infra.repository;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.repository.IUserAppRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserApp;
import com.xspaceagi.userapp.infra.dao.service.IUserAppService;
import com.xspaceagi.userapp.infra.translator.IUserAppTranslator;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

import jakarta.annotation.Resource;

@Repository
public class UserAppRepositoryImpl implements IUserAppRepository {

    @Resource
    private IUserAppService userAppService;
    @Resource
    private IUserAppTranslator userAppTranslator;

    @Override
    public UserAppModel getById(Long id) {
        UserApp entity = userAppService.getOne(Wrappers.<UserApp>lambdaQuery()
                .eq(UserApp::getId, id)
                .last("limit 1"));
        return userAppTranslator.convertToModel(entity);
    }

    @Override
    public long countByCreator(Long creatorId) {
        // 数量配额口径（权威注释在此）：与列表/分页同一条"预删除不可见"规则，口径漂移即列表与配额不一致
        UserAppModel query = new UserAppModel();
        query.setCreatorId(creatorId);
        return userAppService.count(buildQueryWrapper(query));
    }

    @Override
    public List<UserAppModel> list(UserAppModel query) {
        List<UserApp> entities = userAppService.list(buildQueryWrapper(query));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize) {
        Page<UserApp> page = new Page<>(current == null ? 1 : current, pageSize == null ? 10 : pageSize);
        var iPage = userAppService.page(page, buildQueryWrapper(query));
        var records = iPage.getRecords().stream().map(userAppTranslator::convertToModel).collect(Collectors.toList());
        return new SuperPage<>(iPage.getCurrent(), iPage.getSize(), iPage.getTotal(), records);
    }

    @Override
    public Long add(UserAppModel model) {
        UserApp entity = userAppTranslator.convertToEntity(model);
        userAppService.save(entity);
        return entity.getId();
    }

    @Override
    public void updateById(UserAppModel model) {
        UserApp entity = userAppTranslator.convertToEntity(model);
        userAppService.update(entity, Wrappers.<UserApp>lambdaUpdate()
                .eq(UserApp::getId, model.getId()));
    }

    @Override
    public boolean updateBuildVersions(Long id, List<BuildVersionDto> buildVersions, Date expectModified) {
        // JSON 列更新走实体方式，@TableField 的 typeHandler 才会生效
        UserApp entity = new UserApp();
        entity.setId(id);
        entity.setBuildVersions(buildVersions);
        // 以读取时的 modified 做乐观锁（CAS，必填），更新0行返回 false 表示并发冲突；
        // 不允许 null 关闭 CAS——那会退化为无保护的整列覆盖
        Assert.notNull(expectModified, "expectModified must be non-null (CAS on modified)");
        return userAppService.update(entity, Wrappers.<UserApp>lambdaUpdate()
                .eq(UserApp::getId, id)
                .eq(UserApp::getModified, expectModified));
    }

    @Override
    public void updatePublishStatus(Long id, UserAppPublishStatus publishStatus) {
        // 与 updateBuildVersions 一致走实体更新，@TableField 的 typeHandler 才会生效
        UserApp entity = new UserApp();
        entity.setId(id);
        entity.setPublishStatus(publishStatus);
        userAppService.update(entity, Wrappers.<UserApp>lambdaUpdate()
                .eq(UserApp::getId, id));
    }

    @Override
    public void markPreDelete(Long id, Date preDeleteTime) {
        UserApp entity = new UserApp();
        entity.setId(id);
        entity.setPreDeleteTime(preDeleteTime);
        userAppService.update(entity, Wrappers.<UserApp>lambdaUpdate()
                .eq(UserApp::getId, id));
    }

    @Override
    public List<UserAppModel> listPreDeleteDue(Date deadline) {
        // 系统维护任务跨租户扫描
        List<UserApp> entities = TenantFunctions.callWithIgnoreCheck(() ->
                userAppService.list(Wrappers.<UserApp>lambdaQuery()
                        .isNotNull(UserApp::getPreDeleteTime)
                        .le(UserApp::getPreDeleteTime, deadline)));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public boolean existsByPrivateServerId(Long deployServerId) {
        // 仅统计私服模式的应用：platform 的 -1 云端占位不构成私服引用
        return userAppService.count(Wrappers.<UserApp>lambdaQuery()
                .eq(UserApp::getDeployServerId, deployServerId)
                .eq(UserApp::getDeployType, UserAppDeployType.Private)) > 0;
    }

    @Override
    public void updateDeployTarget(Long appId, UserAppDeployType deployType, Long deployServerId) {
        // LambdaUpdateWrapper 显式 set
        userAppService.update(Wrappers.<UserApp>lambdaUpdate()
                .set(UserApp::getDeployType, deployType)
                .set(UserApp::getDeployServerId, deployServerId)
                .eq(UserApp::getId, appId));
    }

    @Override
    public void deleteById(Long id) {
        userAppService.removeById(id);
    }

    private LambdaQueryWrapper<UserApp> buildQueryWrapper(UserAppModel query) {
        UserAppModel q = query == null ? new UserAppModel() : query;
        return Wrappers.<UserApp>lambdaQuery()
                // 预删除项目对列表/分页不可见
                .isNull(UserApp::getPreDeleteTime)
                .eq(q.getSpaceId() != null, UserApp::getSpaceId, q.getSpaceId())
                .eq(q.getCreatorId() != null, UserApp::getCreatorId, q.getCreatorId())
                .eq(q.getPublishStatus() != null, UserApp::getPublishStatus, q.getPublishStatus())
                .like(q.getName() != null, UserApp::getName, q.getName())
                .orderByDesc(UserApp::getModified);
    }
}
