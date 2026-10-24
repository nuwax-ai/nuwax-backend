package com.xspaceagi.system.domain.service;

import com.xspaceagi.system.spec.enums.TenantStatus;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.entity.User;

import java.util.List;

public interface UserDomainService {

    void add(User user);

    void update(User user);

    void delete(Long userId);

    User queryById(Long userId);

    User queryByEmail(String email);

    User queryByPhone(String phone);

    User queryByPhoneAndPassword(String phone, String password);

    User queryByUserName(String userName);

    User queryUserByUid(String uid);

    /**
     * 按「租户 + 账号类型 + 身份关联标识」查询虚拟身份行（account_type 为 agent / group）。
     * <p>与 {@link #queryById} 不同，本方法显式带租户条件：调用方是写路径（惰性创建虚拟身份），
     * 必须自己收口租户，不能依赖用户域刻意的租户豁免。
     *
     * @param tenantId       租户 id
     * @param accountType    账号类型，取 {@code User.ACCOUNT_TYPE_AGENT} / {@code User.ACCOUNT_TYPE_GROUP}
     * @param principalRefId 身份关联标识（agent id / IM 群标识）
     * @return 命中的用户行；不存在返回 null
     */
    User queryByPrincipal(Long tenantId, String accountType, String principalRefId);

    List<User> queryUserListByIds(List<Long> userIds);

    /**
     * 按关键词搜索用户（同租户内；用户名或昵称模糊匹配），按 ID 升序返回。
     *
     * @param kw    关键词（空白返回空列表）
     * @param limit 返回条数上限
     * @return 用户列表
     */
    List<User> searchUsers(String kw, int limit);

    /**
     * 根据用户UID列表查询用户列表,uid是字符串
     * 
     * @param uids 用户UID列表,字符串
     * @return 用户列表
     */
    List<User> queryUserListByUids(List<String> uids);

    List<Long> queryUserIdList(Long lastId, Integer size);
    
   
    /**
     * 获取指定状态的租户列表
     * 
     * @param status 租户状态
     * @return 指定状态的租户列表
     */
    List<Tenant> queryTenantsByStatus(TenantStatus status);
}
