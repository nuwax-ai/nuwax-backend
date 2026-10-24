package com.xspaceagi.userapp.infra.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.domain.repository.IUserProjectWorkspaceRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserProjectWorkspace;
import com.xspaceagi.userapp.infra.dao.service.IUserProjectWorkspaceService;
import com.xspaceagi.userapp.infra.translator.IUserProjectWorkspaceTranslator;

import jakarta.annotation.Resource;

@Repository
public class UserProjectWorkspaceRepositoryImpl implements IUserProjectWorkspaceRepository {

    @Resource
    private IUserProjectWorkspaceService userProjectWorkspaceService;
    @Resource
    private IUserProjectWorkspaceTranslator userProjectWorkspaceTranslator;

    @Override
    public UserProjectWorkspaceModel getByUserProject(Long userId, String projectType, Long projectId) {
        UserProjectWorkspace entity = userProjectWorkspaceService.getOne(Wrappers.<UserProjectWorkspace>lambdaQuery()
                .eq(UserProjectWorkspace::getUserId, userId)
                .eq(UserProjectWorkspace::getProjectType, projectType)
                .eq(UserProjectWorkspace::getProjectId, projectId)
                .last("limit 1"));
        return userProjectWorkspaceTranslator.convertToModel(entity);
    }

    @Override
    public UserProjectWorkspaceModel getOccupied(Long sandboxId, String fileWorkspacePath) {
        UserProjectWorkspace entity = userProjectWorkspaceService.getOne(Wrappers.<UserProjectWorkspace>lambdaQuery()
                .eq(UserProjectWorkspace::getSandboxId, sandboxId)
                .eq(UserProjectWorkspace::getFileWorkspacePath, fileWorkspacePath)
                .last("limit 1"));
        return userProjectWorkspaceTranslator.convertToModel(entity);
    }


    @Override
    public List<UserProjectWorkspaceModel> listByProject(String projectType, Long projectId) {
        return userProjectWorkspaceService.list(Wrappers.<UserProjectWorkspace>lambdaQuery()
                        .eq(UserProjectWorkspace::getProjectType, projectType)
                        .eq(UserProjectWorkspace::getProjectId, projectId))
                .stream().map(userProjectWorkspaceTranslator::convertToModel).collect(Collectors.toList());
    }

    @Override
    public void deleteByProject(String projectType, Long projectId) {
        userProjectWorkspaceService.remove(Wrappers.<UserProjectWorkspace>lambdaQuery()
                .eq(UserProjectWorkspace::getProjectType, projectType)
                .eq(UserProjectWorkspace::getProjectId, projectId));
    }

    @Override
    public Long add(UserProjectWorkspaceModel model) {
        UserProjectWorkspace entity = userProjectWorkspaceTranslator.convertToEntity(model);
        userProjectWorkspaceService.save(entity);
        return entity.getId();
    }
}
