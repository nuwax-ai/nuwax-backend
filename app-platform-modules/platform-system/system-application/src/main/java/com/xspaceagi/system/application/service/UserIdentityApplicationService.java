package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.UserIdentityVo;

import java.util.List;

/**
 * 用户外部身份绑定管理（用户中心，须登录态）
 */
public interface UserIdentityApplicationService {

    /**
     * 查询当前用户所有绑定的外部身份
     */
    List<UserIdentityVo> listMine();

    /**
     * 解绑：最后一个绑定且用户从未设置过密码时拒绝（避免自锁）
     */
    void unbind(Long identityId);
}
