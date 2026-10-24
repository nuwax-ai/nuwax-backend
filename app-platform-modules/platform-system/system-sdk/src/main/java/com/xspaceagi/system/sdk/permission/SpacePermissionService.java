package com.xspaceagi.system.sdk.permission;

import java.util.List;

public interface SpacePermissionService {

    void checkSpaceAdminPermission(Long id);

    void checkSpaceOwnerPermission(Long id);

    void checkSpaceUserPermission(Long id);

    void checkSpaceUserPermission(Long id, Long userId);


    /**
     * 根据用户id,查询用户有权限的空间id集合
     *
     * @param userId 用户id
     * @return 空间id集合
     */
    List<Long> querySpaceIdList(Long userId);

    /**
     * 查询用户在指定空间的角色
     *
     * @param spaceId 空间id
     * @param userId  用户id
     * @return 空间角色(Owner/Admin/User),非空间成员返回 null
     */
    String getSpaceUserRole(Long spaceId, Long userId);
}
