package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.VirtualIdentityDto;

/**
 * 虚拟身份（智能体身份 / 群代理身份）服务。
 * <p>虚拟身份落在 user 表（{@code account_type='agent' | 'group'}），而不是独立表：
 * {@code user_id} 被 40+ 张表引用（记忆、会话、连接器凭证、AK、空间成员…），
 * 只有复用 user 表才能让 agent 全链路「零改动」地按主体隔离数据。
 * 代价是必须把虚拟身份挡在所有「用户」语义的出口之外，见
 * {@code UserApplicationServiceImpl#listQuery} / {@code UserDomainServiceImpl#searchUsers} 等处。
 */
public interface VirtualIdentityApplicationService {

    /**
     * 取指定主体的虚拟身份，不存在则惰性创建。
     * <p><b>幂等</b>：以「租户 + 账号类型 + principalRefId」为幂等键（对应唯一索引
     * {@code uk_tenant_account_ref}）。重复调用返回同一个身份 id，调用方可以放心重试。
     * <p><b>并发</b>：并发首次调用由唯一索引兜底——后到者插入失败后回查已存在行并复用。
     *
     * @param tenantId       租户 id，必填
     * @param accountType    账号类型，只接受 agent / group；不接受 user（本接口不签发自然人）
     * @param principalRefId 身份关联标识，必填且唯一（虚拟身份行必须填写，
     *                       否则唯一索引对 NULL 不生效、并发下会重复创建主体）
     * @param nickName       昵称，可空（空则给一个按类型的通用名）
     * @param avatar         头像，可空
     * @return 身份信息，{@code newlyCreated} 标识本次是否为新建
     */
    VirtualIdentityDto getOrCreateVirtualIdentity(Long tenantId, String accountType, String principalRefId,
            String nickName, String avatar);

    /**
     * <b>只读</b>查询指定主体的虚拟身份，不存在时返回 {@code null}，<b>绝不创建</b>。
     *
     * <p>★为什么要有这个方法（而不是让调用方直接用 {@code getOrCreateVirtualIdentity}）：
     * 「以某个主体的身份说话」这类<b>读路径 / 发消息路径</b>上，惰性创建是<b>副作用</b>——
     * 一次查询就会在 {@code user} 表里凭空多出一个主体，而调用方其实只想知道「它在不在」。
     * 在不在直接决定授权范围（例如「能不能用这个智能体的身份给用户发消息」），
     * 所以这里刻意与写路径分开：查不到就是 {@code null}，由调用方 fail-closed。
     *
     * <p>不过滤 {@code status}：与 {@code UserDomainServiceImpl#queryById} 的读口径一致
     * （平台读路径不按状态筛）。需要「必须是启用态」时由调用方自行判断。
     *
     * @param tenantId       租户 id；为 {@code null} 时直接返回 {@code null}（不带租户条件的查询
     *                       会命中别的租户的行，那等于放大授权范围）
     * @param accountType    账号类型，只接受 agent / group（与写路径同一套归一化，非法值抛
     *                       {@code INVALID_PARAM}，不静默当成查不到）
     * @param principalRefId 身份关联标识，必填
     * @return 身份信息；该主体还没有虚拟身份时为 {@code null}
     */
    VirtualIdentityDto findVirtualIdentity(Long tenantId, String accountType, String principalRefId);
}
