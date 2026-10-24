package com.xspaceagi.userapp.domain.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.domain.repository.IUserAppDomainRepository;
import com.xspaceagi.userapp.domain.service.IUserAppDomainDomainService;
import com.xspaceagi.userapp.sdk.dto.UserAppDomainType;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserAppDomainDomainServiceImpl implements IUserAppDomainDomainService {

    @Resource
    private IUserAppDomainRepository userAppDomainRepository;

    @Override
    public List<UserAppDomainModel> listByAppId(Long appId) {
        return userAppDomainRepository.listByAppId(appId);
    }

    @Override
    public UserAppDomainModel getById(Long id) {
        return userAppDomainRepository.getById(id);
    }

    @Override
    public ReqResult<UserAppDomainModel> create(UserAppDomainModel model, UserContext userContext) {
        // 域名全局唯一（uk_domain 兜底）
        if (userAppDomainRepository.getByDomain(model.getDomain()) != null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainExists);
        }
        model.setTenantId(userContext.getTenantId());
        // 用户接口只产生 Custom 绑定
        model.setDomainType(UserAppDomainType.Custom);
        try {
            userAppDomainRepository.add(model);
        } catch (DuplicateKeyException e) {
            // 并发绑同一域名：唯一索引兜底，转为业务错误而非透出 DB 异常
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainExists);
        }
        log.info("[UserAppDomain] bound, appId={}, domain={}, tenant={}",
                model.getAppId(), model.getDomain(), model.getTenantId());
        return ReqResult.success(model);
    }

    @Override
    public ReqResult<UserAppDomainModel> update(UserAppDomainModel model, UserContext userContext) {
        // getById 已受租户拦截器约束（where id=? and _tenant_id=?），跨租户 id 查不出按不存在处理
        UserAppDomainModel existing = userAppDomainRepository.getById(model.getId());
        if (existing == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainNotFound);
        }
        if (existing.getDomainType() != UserAppDomainType.Custom) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainDefaultImmutable);
        }
        UserAppDomainModel duplicate = userAppDomainRepository.getByDomain(model.getDomain());
        if (duplicate != null && !duplicate.getId().equals(model.getId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainExists);
        }
        existing.setDomain(model.getDomain());
        userAppDomainRepository.updateById(existing);
        return ReqResult.success(existing);
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        // 同 update：租户隔离由拦截器保证
        UserAppDomainModel existing = userAppDomainRepository.getById(id);
        if (existing == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainNotFound);
        }
        if (existing.getDomainType() != UserAppDomainType.Custom) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainDefaultImmutable);
        }
        userAppDomainRepository.deleteById(id);
        log.info("[UserAppDomain] unbound, id={}, appId={}, domain={}", id, existing.getAppId(), existing.getDomain());
        return ReqResult.success(null);
    }

    @Override
    public List<String> listAllDomains() {
        return userAppDomainRepository.listAll().stream()
                .map(UserAppDomainModel::getDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteByAppId(Long appId) {
        userAppDomainRepository.deleteByAppId(appId);
        log.info("[UserAppDomain] all bindings removed for app purge, appId={}", appId);
    }

    @Override
    public void ensureSystemBinding(Long appId, Long tenantId, String domainSuffix, UserAppDomainType domainType) {
        // 幂等（按应用+类型）：随机域名无法像固定域名那样靠确定性字符串幂等，重复创建/发布保持原域名不换
        if (userAppDomainRepository.listByAppId(appId).stream()
                .anyMatch(binding -> binding.getDomainType() == domainType)) {
            return;
        }
        // 随机前缀（防按 appId 枚举）：撞串或并发冲突换串重试
        for (int attempt = 1; attempt <= RANDOM_DOMAIN_ATTEMPTS; attempt++) {
            String domain = randomSlug() + domainSuffix;
            if (userAppDomainRepository.getByDomain(domain) != null) {
                continue;
            }
            UserAppDomainModel model = new UserAppDomainModel();
            model.setTenantId(tenantId);
            model.setAppId(appId);
            model.setDomain(domain);
            model.setDomainType(domainType);
            try {
                userAppDomainRepository.add(model);
                log.info("[UserAppDomain] system domain assigned, type={}, appId={}, domain={}, tenant={}",
                        domainType, appId, domain, tenantId);
                return;
            } catch (DuplicateKeyException e) {
                // 并发撞串：换串重试
            }
        }
        log.error("[UserAppDomain] random domain allocation exhausted, appId={}, type={}, attempts={}",
                appId, domainType, RANDOM_DOMAIN_ATTEMPTS);
        throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.userappDomainExists);
    }

    /** 随机域名撞串重试次数（32 字符集长度 10，单次撞串概率 ~1e-15，5 次仅为兜底） */
    private static final int RANDOM_DOMAIN_ATTEMPTS = 5;

    /** 随机串字符集：小写字母+数字，剔除易混淆字符（0/o、1/l/i），且与大小写不敏感的唯一键兼容 */
    private static final String RANDOM_SLUG_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String randomSlug() {
        StringBuilder slug = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            slug.append(RANDOM_SLUG_CHARS.charAt(RANDOM.nextInt(RANDOM_SLUG_CHARS.length())));
        }
        return slug.toString();
    }
}
