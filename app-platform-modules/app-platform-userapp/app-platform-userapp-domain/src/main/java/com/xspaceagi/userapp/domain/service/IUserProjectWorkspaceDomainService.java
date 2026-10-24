package com.xspaceagi.userapp.domain.service;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;

public interface IUserProjectWorkspaceDomainService {

    /** 按 用户+项目 查绑定 */
    UserProjectWorkspaceModel getByUserProject(Long userId, String projectType, Long projectId);

    /** 目录占用查询：同沙箱同目录的绑定（跨用户） */
    UserProjectWorkspaceModel getOccupied(Long sandboxId, String fileWorkspacePath);

    /** 项目的全部用户工作区绑定（不同用户可能使用不同云端沙箱，逐用户清理用） */
    List<UserProjectWorkspaceModel> listByProject(String projectType, Long projectId);

    /** 删除项目的全部用户工作区绑定 */
    void deleteByProject(String projectType, Long projectId);

    Long add(UserProjectWorkspaceModel model);
}
