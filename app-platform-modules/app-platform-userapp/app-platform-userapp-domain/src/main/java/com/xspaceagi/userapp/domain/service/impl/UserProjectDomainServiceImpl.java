package com.xspaceagi.userapp.domain.service.impl;

import org.springframework.stereotype.Service;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.repository.IUserProjectRepository;
import com.xspaceagi.userapp.domain.service.IUserProjectDomainService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Service
public class UserProjectDomainServiceImpl implements IUserProjectDomainService {

    @Resource
    private IUserProjectRepository userProjectRepository;

    @Override
    public UserProjectModel register(ProjectTypeEnum projectType, Long refId, String name, String description,
            String icon, Long spaceId, Long creatorId, Long sandboxId, Long devAgentId, Boolean nameDefined) {
        UserProjectModel existing = userProjectRepository.getByRef(projectType, refId);
        if (existing != null) {
            // 已登记：更新展示字段与空间（源项目可能被移动空间/改名）
            UserProjectModel update = new UserProjectModel();
            update.setId(existing.getId());
            update.setName(name);
            update.setDescription(description);
            update.setIcon(icon);
            update.setSpaceId(spaceId);
            update.setSandboxId(sandboxId);
            // nameDefined 只涨不降：幂等更新分支仅在明确传入 true 时置位，不回退既有标记
            if (Boolean.TRUE.equals(nameDefined)) {
                update.setNameDefined(true);
            }
            userProjectRepository.updateById(update);
            log.info("User project registered(updated), type={}, refId={}, id={}", projectType, refId, existing.getId());
            return userProjectRepository.getById(existing.getId());
        }
        UserProjectModel model = new UserProjectModel();
        model.setProjectType(projectType);
        model.setRefId(refId);
        model.setName(name);
        model.setDescription(description);
        model.setIcon(icon);
        model.setSpaceId(spaceId);
        model.setCreatorId(creatorId);
        model.setSandboxId(sandboxId);
        model.setDevAgentId(devAgentId);
        // null 视为已命名：未指定时保守不触发前端自动命名，避免覆盖外部登记的真实名
        model.setNameDefined(nameDefined == null || nameDefined);
        Long id = userProjectRepository.add(model);
        model.setId(id);
        log.info("User project registered(inserted), type={}, refId={}, id={}", projectType, refId, id);
        return model;
    }

    @Override
    public void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon) {
        if (name == null && description == null && icon == null) {
            // 无字段可同步：跳过，避免生成空 SET 子句
            return;
        }
        UserProjectModel existing = userProjectRepository.getByRef(projectType, refId);
        if (existing == null) {
            // 源表存在但未登记（历史数据）：跳过，不报错
            log.warn("User project not registered, skip sync, type={}, refId={}", projectType, refId);
            return;
        }
        UserProjectModel update = new UserProjectModel();
        update.setId(existing.getId());
        update.setName(name);
        // 源表改名同步即视为名称已定制（userapp update → syncBasicInfo 链路）；name 为 null 说明本次未改名，不置位
        if (name != null) {
            update.setNameDefined(true);
        }
        update.setDescription(description);
        update.setIcon(icon);
        userProjectRepository.updateById(update);
    }

    @Override
    public void unregister(ProjectTypeEnum projectType, Long refId) {
        userProjectRepository.deleteByRef(projectType, refId);
    }

    @Override
    public UserProjectModel create(UserProjectModel model) {
        Long id = userProjectRepository.add(model);
        model.setId(id);
        return model;
    }

    @Override
    public UserProjectModel update(UserProjectModel model) {
        userProjectRepository.updateById(model);
        return userProjectRepository.getById(model.getId());
    }

    @Override
    public void delete(Long id) {
        userProjectRepository.deleteById(id);
    }

    @Override
    public UserProjectModel getById(Long id) {
        return userProjectRepository.getById(id);
    }

    @Override
    public UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId) {
        return userProjectRepository.getByRef(projectType, refId);
    }

    @Override
    public UserProjectModel getByClientId(String oauthClientId) {
        return userProjectRepository.getByOAuthClientId(oauthClientId);
    }


    @Override
    public List<UserProjectModel> list(UserProjectModel query) {
        return userProjectRepository.list(query);
    }

    @Override
    public SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize) {
        return userProjectRepository.pageQuery(query, current, pageSize);
    }
}
