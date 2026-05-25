package com.xspaceagi.system.sdk.server;


import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.common.UserContext;

import java.util.List;

/**
 * 用户查询接口
 */
public interface IUserRpcService {

    /**
     * 根据用户id,查询用户信息
     *
     * @param userIds 用户id集合
     * @return
     */
    List<UserContext> queryUserListByIds(List<Long> userIds);

    UserDetailDto queryUserDetailByName(String userName);

    UserDetailDto queryUserDetailById(Long userId);

    String getUserDynamicCode(Long userId);
}
