package com.xspaceagi.userapp.domain.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.domain.repository.IUserProjectWorkspaceRepository;
import com.xspaceagi.userapp.domain.service.IUserProjectWorkspaceDomainService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserProjectWorkspaceDomainServiceImpl implements IUserProjectWorkspaceDomainService {

    @Resource
    private IUserProjectWorkspaceRepository userProjectWorkspaceRepository;

    @Override
    public UserProjectWorkspaceModel getByUserProject(Long userId, String projectType, Long projectId) {
        return userProjectWorkspaceRepository.getByUserProject(userId, projectType, projectId);
    }

    @Override
    public UserProjectWorkspaceModel getOccupied(Long sandboxId, String fileWorkspacePath) {
        return userProjectWorkspaceRepository.getOccupied(sandboxId, fileWorkspacePath);
    }

    @Override
    public List<UserProjectWorkspaceModel> listByProject(String projectType, Long projectId) {
        return userProjectWorkspaceRepository.listByProject(projectType, projectId);
    }

    @Override
    public void deleteByProject(String projectType, Long projectId) {
        userProjectWorkspaceRepository.deleteByProject(projectType, projectId);
    }

    @Override
    public Long add(UserProjectWorkspaceModel model) {
        return userProjectWorkspaceRepository.add(model);
    }
}
