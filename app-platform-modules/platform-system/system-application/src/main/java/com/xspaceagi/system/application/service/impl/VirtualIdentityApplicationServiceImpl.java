package com.xspaceagi.system.application.service.impl;

import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.xspaceagi.system.application.dto.VirtualIdentityDto;
import com.xspaceagi.system.application.service.VirtualIdentityApplicationService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 虚拟身份的取用与惰性创建。
 * <p><b>刻意不加 {@code @Transactional}</b>：唯一索引兜底并发依赖「插入失败后回查」，
 * 若把插入包在事务里，一次唯一键冲突就会把事务标记为 rollback-only，回查必然失败。
 * 这里的写操作都是单语句，自动提交即可。
 */
@Service
@Slf4j
public class VirtualIdentityApplicationServiceImpl implements VirtualIdentityApplicationService {

    /** principal_ref_id 列宽 */
    private static final int MAX_PRINCIPAL_REF_LENGTH = 128;

    /** nick_name 列宽 */
    private static final int MAX_NICK_NAME_LENGTH = 64;

    /** avatar 列宽 */
    private static final int MAX_AVATAR_LENGTH = 255;

    private static final String DEFAULT_NICK_NAME_AGENT = "智能体";

    private static final String DEFAULT_NICK_NAME_GROUP = "群代理";

    @Resource
    private UserDomainService userDomainService;

    @Override
    public VirtualIdentityDto getOrCreateVirtualIdentity(Long tenantId, String accountType, String principalRefId,
            String nickName, String avatar) {
        Assert.notNull(tenantId, "tenantId must be non-null");
        String type = normalizeAccountType(accountType);
        String ref = normalizePrincipalRefId(principalRefId);
        String name = normalizeNickName(nickName, type);
        String icon = normalizeAvatar(avatar);

        User existing = userDomainService.queryByPrincipal(tenantId, type, ref);
        if (existing != null) {
            backfillUidIfBlank(existing);
            log.info("Virtual identity reused, tenantId={}, accountType={}, principalRefId={}, userId={}",
                    tenantId, type, ref, existing.getId());
            return toDto(existing, false);
        }

        User user = buildVirtualIdentity(tenantId, type, ref, name, icon);
        try {
            userDomainService.add(user);
        } catch (DuplicateKeyException e) {
            // 并发下别的请求抢先建好了：唯一索引 uk_tenant_account_ref 拦下本次插入，回查复用
            User created = userDomainService.queryByPrincipal(tenantId, type, ref);
            if (created == null) {
                // 冲突的不是本主体（例如别处写了同 ref 的其他类型），不是可自愈的场景，原样抛出
                throw e;
            }
            backfillUidIfBlank(created);
            log.info("Virtual identity created concurrently by another request, tenantId={}, accountType={}, "
                    + "principalRefId={}, userId={}", tenantId, type, ref, created.getId());
            return toDto(created, false);
        }
        backfillUidIfBlank(user);
        // 回查一次取 DB 生成的 created（内存实体上该字段为空，直接返回会让「首次创建」的
        // createTime 为 null、「复用」的却有值，同一字段两种表现）
        User persisted = userDomainService.queryByPrincipal(tenantId, type, ref);
        log.info("Virtual identity created, tenantId={}, accountType={}, principalRefId={}, userId={}",
                tenantId, type, ref, user.getId());
        return toDto(persisted == null ? user : persisted, true);
    }

    @Override
    public VirtualIdentityDto findVirtualIdentity(Long tenantId, String accountType, String principalRefId) {
        if (tenantId == null) {
            // ★不带租户条件就查，会命中别的租户里同 ref 的行 —— 在「以谁的身份说话」这类判据上，
            //   那等于放大授权范围。租户未知一律按「查不到」处理，由调用方 fail-closed。
            log.warn("Virtual identity lookup skipped: tenantId is null, accountType={}, principalRefId={}",
                    accountType, principalRefId);
            return null;
        }
        String type = normalizeAccountType(accountType);
        String ref = normalizePrincipalRefId(principalRefId);
        User existing = userDomainService.queryByPrincipal(tenantId, type, ref);
        if (existing == null) {
            log.info("Virtual identity not found, tenantId={}, accountType={}, principalRefId={}",
                    tenantId, type, ref);
            return null;
        }
        // ★只读：不调 backfillUidIfBlank（那是写路径的自愈动作，读路径不该有写副作用）
        return toDto(existing, false);
    }

    /**
     * 构造虚拟身份行。
     * <p>三个关键取舍：
     * <ol>
     *   <li><b>userName / phone / email 一律留空</b>：登录面按用户名/手机号/邮箱找人，
     *       留空后这些路径天然命中不到虚拟身份，不必依赖额外的登录拦截。</li>
     *   <li><b>role 固定 User，绝不给 Admin</b>：租户初始化会取 role=Admin 的用户来建默认智能体，
     *       虚拟身份若为 Admin 会造成误挂。</li>
     *   <li><b>password 写随机明文</b>：该列 NOT NULL 无默认值，只能给值；
     *       随机 UUID 经 {@code UserDomainServiceImpl#add} 的 MD5 强加密后落库，
     *       调用方无从得知明文，任何口令都匹配不上。</li>
     * </ol>
     */
    private static User buildVirtualIdentity(Long tenantId, String type, String ref, String name, String icon) {
        User user = new User();
        // 显式写租户，不依赖 MP 租户拦截器自动填充：本表读路径刻意豁免租户过滤，写路径自己收口
        user.setTenantId(tenantId);
        user.setAccountType(type);
        user.setPrincipalRefId(ref);
        user.setNickName(name);
        user.setAvatar(icon);
        user.setRole(User.Role.User);
        // Enabled：虚拟身份要被 queryById 查到并作为主体参与链路，不能是 Disabled
        user.setStatus(User.Status.Enabled);
        user.setResetPass(0);
        user.setPassword(UUID.randomUUID().toString());
        return user;
    }

    /**
     * 补 uid。
     * <p>平台约定「无外部 uid 的主体取 id 字符串」，但 id 要插入后才有，故这里补一次。
     * 用只带 id+uid 的新实体走 updateById：既只更新 uid 一列，也避免把 {@code add} 已加密的
     * password 再加密一次（{@code UserDomainServiceImpl#update} 对非空密码会重新加密）。
     * <p>在「复用」分支同样调用，使早期写入失败的行可以自愈。
     */
    private void backfillUidIfBlank(User user) {
        if (user.getId() == null || StringUtils.isNotBlank(user.getUid())) {
            return;
        }
        String uid = String.valueOf(user.getId());
        User patch = new User();
        patch.setId(user.getId());
        patch.setUid(uid);
        userDomainService.update(patch);
        user.setUid(uid);
    }

    /**
     * 账号类型白名单：只接受 agent / group。
     * <p>刻意不接受 user——本接口不签发自然人身份，否则等于给调用方开了「凭空造人」的口子。
     */
    private static String normalizeAccountType(String accountType) {
        String type = StringUtils.trimToNull(accountType);
        if (type == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "accountType");
        }
        type = type.toLowerCase(Locale.ROOT);
        if (!User.ACCOUNT_TYPE_AGENT.equals(type) && !User.ACCOUNT_TYPE_GROUP.equals(type)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "accountType 只支持 " + User.ACCOUNT_TYPE_AGENT + " / " + User.ACCOUNT_TYPE_GROUP,
                    "accountType only supports " + User.ACCOUNT_TYPE_AGENT + " or " + User.ACCOUNT_TYPE_GROUP + ".");
        }
        return type;
    }

    /** 身份关联标识必填：虚拟身份行不填 ref，唯一索引对 NULL 不生效，并发下会重复建主体 */
    private static String normalizePrincipalRefId(String principalRefId) {
        String ref = StringUtils.trimToNull(principalRefId);
        if (ref == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "principalRefId");
        }
        if (ref.length() > MAX_PRINCIPAL_REF_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "principalRefId 超出最大长度 " + MAX_PRINCIPAL_REF_LENGTH,
                    "principalRefId exceeds max length " + MAX_PRINCIPAL_REF_LENGTH + ".");
        }
        return ref;
    }

    private static String normalizeNickName(String nickName, String type) {
        String name = StringUtils.trimToNull(nickName);
        if (name == null) {
            return User.ACCOUNT_TYPE_GROUP.equals(type) ? DEFAULT_NICK_NAME_GROUP : DEFAULT_NICK_NAME_AGENT;
        }
        if (name.length() > MAX_NICK_NAME_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "nickName 超出最大长度 " + MAX_NICK_NAME_LENGTH,
                    "nickName exceeds max length " + MAX_NICK_NAME_LENGTH + ".");
        }
        return name;
    }

    private static String normalizeAvatar(String avatar) {
        String icon = StringUtils.trimToNull(avatar);
        if (icon != null && icon.length() > MAX_AVATAR_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "avatar 超出最大长度 " + MAX_AVATAR_LENGTH,
                    "avatar exceeds max length " + MAX_AVATAR_LENGTH + ".");
        }
        return icon;
    }

    private static VirtualIdentityDto toDto(User user, boolean newlyCreated) {
        VirtualIdentityDto dto = new VirtualIdentityDto();
        dto.setId(user.getId());
        dto.setUid(user.getUid());
        dto.setTenantId(user.getTenantId());
        dto.setAccountType(user.getAccountType());
        dto.setPrincipalRefId(user.getPrincipalRefId());
        dto.setNickName(user.getNickName());
        dto.setAvatar(user.getAvatar());
        dto.setStatus(user.getStatus());
        dto.setNewlyCreated(newlyCreated);
        dto.setCreateTime(user.getCreated());
        return dto;
    }
}
