package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.AuthIdpSaveDto;
import com.xspaceagi.system.application.dto.AuthIdpVo;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import java.util.List;

/**
 * 登录方式配置管理（系统管理端）
 */
public interface AuthIdpConfigApplicationService {

    ReqResult<AuthIdpVo> create(AuthIdpSaveDto dto, UserContext userContext);

    ReqResult<AuthIdpVo> update(AuthIdpSaveDto dto, UserContext userContext);

    ReqResult<Void> delete(Long id, UserContext userContext);

    ReqResult<List<AuthIdpVo>> list(UserContext userContext);

    /**
     * 启用/停用；停用「未登录自动跳转」目标时联动取消
     */
    ReqResult<Void> updateStatus(Long id, Integer enabled, UserContext userContext);

    /**
     * 设置/取消未登录自动跳转目标（租户内全局唯一）；id=null 表示取消
     */
    ReqResult<Void> setAutoRedirect(Long id, UserContext userContext);
}
