package com.xspaceagi.system.application.service;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.domain.model.GroupBindMenuModel;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.UserContext;

/**
 * 用户组应用服务
 */
public interface SysGroupApplicationService {

    /**
     * 添加用户组
     */
    void addGroup(SysGroup group, UserContext userContext);

    /**
     * 更新用户组
     */
    void updateGroup(SysGroup group, UserContext userContext);

    /**
     * 用户组绑定数据权限（全量覆盖）
     */
    void bindDataPermission(Long groupId, SysDataPermission dataPermission, UserContext userContext);

    /**
     * 删除用户组
     */
    void deleteGroup(Long groupId, UserContext userContext);

    /**
     * 根据ID查询用户组
     */
    SysGroup getGroupById(Long groupId);

    /**
     * 根据ID批量查询用户组
     */
    List<SysGroup> listGroupsByIds(List<Long> groupIds);

    /**
     * 根据编码查询用户组
     */
    SysGroup getGroupByCode(String groupCode);

    /**
     * 查询用户组列表
     */
    List<SysGroup> getGroupList(SysGroup group);

    /**
     * 根据用户组ID查询用户列表
     */
    List<User> getUserListByGroupId(Long groupId);

    /**
     * 根据用户组ID分页查询用户列表，支持按userName模糊筛选
     */
    IPage<User> getUserPageByGroupId(Long groupId, String userName, long pageNo, long pageSize);

    /**
     * 根据用户ID查询用户组列表
     */
    List<SysGroup> getGroupListByUserId(Long userId);

    /**
     * 查询用户有效用户组（数据库绑定 + 当前系统订阅计划关联的用户组，仅启用状态）
     */
    List<SysGroup> getEffectiveGroupListByUserId(Long userId);

    /**
     * 用户组绑定用户（全量覆盖）
     */
    void groupBindUser(Long groupId, List<Long> userIds, UserContext userContext);

    /**
     * 用户组添加用户
     */
    void groupAddUser(Long groupId, Long userId, UserContext userContext);

    /**
     * 用户组移除用户
     */
    void groupRemoveUser(Long groupId, Long userId, UserContext userContext);

    /**
     * 用户绑定用户组（全量覆盖）
     */
    void userBindGroup(Long userId, List<Long> groupIds, UserContext userContext);

    /**
     * 绑定菜单权限（全量覆盖）
     */
    void bindMenu(GroupBindMenuModel model, UserContext userContext);

    /**
     * 查询角色已绑定的菜单树（包含资源权限）
     */
    List<MenuNode> getMenuTreeByGroupId(Long groupId);

    /**
     * 批量调整用户组顺序
     */
    void batchUpdateGroupSort(List<SortIndex> sortIndexList, UserContext userContext);

}


