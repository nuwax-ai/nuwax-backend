package com.xspaceagi.userapp.domain.repository;

import java.util.Date;
import java.util.List;

import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

public interface IUserPrivateServerRepository {

    UserPrivateServerModel getById(Long id);

    /** 属主名下全部私服（跨项目复用列表） */
    List<UserPrivateServerModel> listByOwner(Long tenantId, Long ownerUserId);

    Long add(UserPrivateServerModel model);

    void updateById(UserPrivateServerModel model);

    /** 更新状态与健康检查时间（显式 set） */
    void updateStatus(Long id, UserPrivateServerStatus status, Date lastHealthCheck);

    /** 仅刷新健康检查时间 */
    void updateLastHealthCheck(Long id, Date lastHealthCheck);

    /** 更新私服容器版本（健康检查时经 /version 探测回填） */
    void updateVersion(Long id, String version);

    void deleteById(Long id);
}
