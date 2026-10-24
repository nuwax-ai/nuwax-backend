package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerDto;

public interface IUserPrivateServerApplicationService {

    /** 登记私服 */
    UserPrivateServerDto register(UserPrivateServerModel model);

    /** 更新 */
    UserPrivateServerDto update(Long operatorUserId, UserPrivateServerModel model);

    void delete(Long operatorUserId, Long id);

    /** 属主名下私服列表（脱敏） */
    List<UserPrivateServerDto> listMine();

    /** 详情（脱敏；属主校验） */
    UserPrivateServerDto get(Long operatorUserId, Long id);

    /**
     * 健康检查/health 并落库状态
     *
     * @return true 表示在线
     */
    boolean checkHealth(Long operatorUserId, Long id);

    /** 设置应用发布部署目标 */
    void setAppDeployTarget(Long appId, UserAppDeployType deployType, Long deployServerId, Long operatorUserId);
}
