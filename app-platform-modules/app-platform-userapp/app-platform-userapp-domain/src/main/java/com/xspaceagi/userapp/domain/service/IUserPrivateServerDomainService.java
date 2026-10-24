package com.xspaceagi.userapp.domain.service;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;

public interface IUserPrivateServerDomainService {

    /**
     * 登记私服，初始状态 unverified
     */
    UserPrivateServerModel register(UserPrivateServerModel model);

    /** 更新 */
    UserPrivateServerModel update(Long operatorUserId, UserPrivateServerModel model);

    /** 删除（仍被应用引用时拒绝） */
    void delete(Long operatorUserId, Long id);

    /** 属主名下私服列表 */
    List<UserPrivateServerModel> listByOwner(Long tenantId, Long ownerUserId);

    UserPrivateServerModel getById(Long id);

    /** 健康检查结果落库（online/offline） */
    void markStatus(Long id, boolean online);

    /** 私服容器版本落库（健康检查在线时经 /version 探测回填） */
    void saveVersion(Long id, String version);
}
