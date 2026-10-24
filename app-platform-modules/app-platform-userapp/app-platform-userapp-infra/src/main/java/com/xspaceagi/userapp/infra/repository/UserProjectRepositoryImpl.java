package com.xspaceagi.userapp.infra.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.repository.IUserProjectRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserProject;
import com.xspaceagi.userapp.infra.dao.service.IUserProjectService;
import com.xspaceagi.userapp.infra.translator.IUserProjectTranslator;

import jakarta.annotation.Resource;

@Repository
public class UserProjectRepositoryImpl implements IUserProjectRepository {

    @Resource
    private IUserProjectService userProjectService;
    @Resource
    private IUserProjectTranslator userProjectTranslator;

    @Override
    public UserProjectModel getById(Long id) {
        UserProject entity = userProjectService.getOne(Wrappers.<UserProject>lambdaQuery()
                .eq(UserProject::getId, id)
                .last("limit 1"));
        return userProjectTranslator.convertToModel(entity);
    }

    @Override
    public UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId) {
        UserProject entity = userProjectService.getOne(Wrappers.<UserProject>lambdaQuery()
                .eq(UserProject::getProjectType, projectType)
                .eq(UserProject::getRefId, refId)
                .last("limit 1"));
        return userProjectTranslator.convertToModel(entity);
    }


    @Override
    public UserProjectModel getByOAuthClientId(String oauthClientId) {
        if (oauthClientId == null) {
            return null;
        }
        // 接入应用不跨租户：沿用多租户过滤，只在本租户内按 client_id 反查。
        // 协议请求（含免登录的 /api/oauth2/token）与设置页一样由域名/x-tenant-id 解析出租户，
        // 解析不到租户时查不到客户端，以 invalid_client 失败（fail-closed），不做跨租户兜底
        UserProject entity = userProjectService.getOne(Wrappers.<UserProject>lambdaQuery()
                .eq(UserProject::getOauthClientId, oauthClientId)
                .last("limit 1"));
        return userProjectTranslator.convertToModel(entity);
    }

    @Override
    public List<UserProjectModel> list(UserProjectModel query) {
        List<UserProject> entities = userProjectService.list(buildQueryWrapper(query));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userProjectTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize) {
        Page<UserProject> page = new Page<>(current == null ? 1 : current, pageSize == null ? 10 : pageSize);
        var iPage = userProjectService.page(page, buildQueryWrapper(query));
        var records = iPage.getRecords().stream().map(userProjectTranslator::convertToModel).collect(Collectors.toList());
        return new SuperPage<>(iPage.getCurrent(), iPage.getSize(), iPage.getTotal(), records);
    }

    @Override
    public Long add(UserProjectModel model) {
        UserProject entity = userProjectTranslator.convertToEntity(model);
        userProjectService.save(entity);
        return entity.getId();
    }

    @Override
    public void updateById(UserProjectModel model) {
        UserProject entity = userProjectTranslator.convertToEntity(model);
        userProjectService.update(entity, Wrappers.<UserProject>lambdaUpdate()
                .eq(UserProject::getId, model.getId()));
    }

    @Override
    public void deleteById(Long id) {
        userProjectService.removeById(id);
    }

    @Override
    public void deleteByRef(ProjectTypeEnum projectType, Long refId) {
        userProjectService.remove(Wrappers.<UserProject>lambdaQuery()
                .eq(UserProject::getProjectType, projectType)
                .eq(UserProject::getRefId, refId));
    }

    private LambdaQueryWrapper<UserProject> buildQueryWrapper(UserProjectModel query) {
        UserProjectModel q = query == null ? new UserProjectModel() : query;
        return Wrappers.<UserProject>lambdaQuery()
                .eq(q.getSpaceId() != null, UserProject::getSpaceId, q.getSpaceId())
                .in(q.getSpaceIds() != null && !q.getSpaceIds().isEmpty(),
                        UserProject::getSpaceId, q.getSpaceIds())
                // 主键 IN 过滤（收藏过滤等）；空集合由调用方短路，不进查询
                .in(q.getIds() != null && !q.getIds().isEmpty(),
                        UserProject::getId, q.getIds())
                .in(q.getRefIds() != null && !q.getRefIds().isEmpty(),
                        UserProject::getRefId, q.getRefIds())
                .eq(q.getCreatorId() != null, UserProject::getCreatorId, q.getCreatorId())
                .eq(q.getProjectType() != null, UserProject::getProjectType, q.getProjectType())
                .in(q.getProjectTypes() != null && !q.getProjectTypes().isEmpty(),
                        UserProject::getProjectType, q.getProjectTypes())
                .like(q.getName() != null, UserProject::getName, q.getName())
                // 归档列等值匹配：false=未归档（列表默认）；true=仅归档（归档管理）；null=不过滤
                .eq(q.getArchivedEq() != null, UserProject::getArchived, q.getArchivedEq())
                // 置顶排最前，同组内按修改时间倒序
                .orderByDesc(UserProject::getPinned)
                .orderByDesc(UserProject::getModified);
    }
}
