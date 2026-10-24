package com.xspaceagi.system.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.application.dto.AuthIdpSaveDto;
import com.xspaceagi.system.application.dto.AuthIdpVo;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.AuthIdpConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;
import com.xspaceagi.system.infra.dao.service.AuthIdpConfigService;
import com.xspaceagi.system.infra.dao.service.UserAuthIdentityService;
import com.xspaceagi.system.infra.idp.CasIdpConfig;
import com.xspaceagi.system.infra.idp.IdpConfigParser;
import com.xspaceagi.system.infra.idp.OAuth2IdpConfig;
import com.xspaceagi.system.infra.idp.WeChatIdpConfig;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.OAuth2ProviderEnum;
import com.xspaceagi.system.spec.enums.WeChatIdpModeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthIdpConfigApplicationServiceImpl implements AuthIdpConfigApplicationService {

    private static final String SECRET_MASK = "******";
    private static final String CALLBACK_PATH = "/api/auth/idp/callback";

    @Resource
    private AuthIdpConfigService authIdpConfigService;

    @Resource
    private UserAuthIdentityService userAuthIdentityService;

    @Resource
    private com.xspaceagi.system.infra.idp.IdpBrandIcon idpBrandIcon;

    @Override
    public ReqResult<AuthIdpVo> create(AuthIdpSaveDto dto, UserContext userContext) {
        requireField(dto, "请求参数");
        requireField(dto.getName(), "配置名称");
        requireField(dto.getType(), "类型");
        log.info("[create] Create auth idp, type={}, name={}", dto.getType(), dto.getName());
        AuthIdpTypeEnum type = parseType(dto.getType());
        if (type == null) {
            return ReqResult.error("4000", "登录方式类型无效");
        }
        AuthIdpConfig row = new AuthIdpConfig();
        row.setTenantId(userContext.getTenantId());
        row.setType(type.getCode());
        row.setName(dto.getName().trim());
        row.setIcon(StringUtils.trimToNull(dto.getIcon()));
        // 新增默认停用，手动启用（防半成品配置直接暴露到登录页）
        row.setEnabled(0);
        row.setAutoRedirect(0);
        row.setAutoRegisterBind(dto.getAutoRegisterBind() != null && dto.getAutoRegisterBind() == 1 ? 1 : 0);
        row.setSort(dto.getSort() == null ? 0 : dto.getSort());
        String configError = applyConfig(row, dto, null);
        if (configError != null) {
            return ReqResult.error("4000", configError);
        }
        authIdpConfigService.save(row);
        log.info("[create] 创建登录方式成功, id={}", row.getId());
        return ReqResult.success(toVo(row));
    }

    @Override
    public ReqResult<AuthIdpVo> update(AuthIdpSaveDto dto, UserContext userContext) {
        requireField(dto, "请求参数");
        requireField(dto.getId(), "ID");
        log.info("[update] Update auth idp, id={}, name={}", dto.getId(), dto.getName());
        AuthIdpConfig existing = authIdpConfigService.getById(dto.getId());
        if (existing == null) {
            return ReqResult.error("4000", "登录方式不存在");
        }
        AuthIdpTypeEnum oldType = parseType(existing.getType());
        AuthIdpTypeEnum newType = parseType(dto.getType());
        if (newType == null || oldType != newType) {
            // 绑定关系按 idp_id + external_id 存储，类型变更语义不成立
            return ReqResult.error("4000", "登录方式类型不允许变更");
        }
        if (StringUtils.isNotBlank(dto.getName())) {
            existing.setName(dto.getName().trim());
        }
        existing.setIcon(StringUtils.trimToNull(dto.getIcon()));
        if (dto.getSort() != null) {
            existing.setSort(dto.getSort());
        }
        if (dto.getAutoRegisterBind() != null) {
            existing.setAutoRegisterBind(dto.getAutoRegisterBind() == 1 ? 1 : 0);
        }
        String configError = applyConfig(existing, dto, existing.getConfig());
        if (configError != null) {
            return ReqResult.error("4000", configError);
        }
        authIdpConfigService.updateById(existing);
        log.info("[update] 更新登录方式成功, id={}", existing.getId());
        return ReqResult.success(toVo(existing));
    }

    @Override
    public ReqResult<Void> delete(Long id, UserContext userContext) {
        requireField(id, "ID");
        log.info("[delete] Delete auth idp, id={}", id);
        AuthIdpConfig existing = authIdpConfigService.getById(id);
        if (existing == null) {
            return ReqResult.error("4000", "登录方式不存在");
        }
        Long boundUsers = userAuthIdentityService.lambdaQuery()
                .eq(UserAuthIdentity::getIdpId, id)
                .count();
        if (boundUsers != null && boundUsers > 0) {
            return ReqResult.error("4000", "仍有 " + boundUsers + " 个用户绑定该登录方式，请先让用户解绑后再删除");
        }
        authIdpConfigService.removeById(id);
        return ReqResult.success();
    }

    @Override
    public ReqResult<List<AuthIdpVo>> list(UserContext userContext) {
        List<AuthIdpConfig> rows = authIdpConfigService.lambdaQuery()
                .orderByAsc(AuthIdpConfig::getSort, AuthIdpConfig::getId)
                .list();
        return ReqResult.success(rows.stream().map(this::toVo).collect(Collectors.toList()));
    }

    @Override
    public ReqResult<Void> updateStatus(Long id, Integer enabled, UserContext userContext) {
        requireField(id, "ID");
        requireField(enabled, "状态");
        log.info("[updateStatus] Update auth idp status, id={}, enabled={}", id, enabled);
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            return ReqResult.error("4000", "状态值无效");
        }
        AuthIdpConfig existing = authIdpConfigService.getById(id);
        if (existing == null) {
            return ReqResult.error("4000", "登录方式不存在");
        }
        AuthIdpConfig update = new AuthIdpConfig();
        update.setId(id);
        update.setEnabled(enabled);
        // 停用联动取消未登录自动跳转，避免跳转到不可用登录方式
        if (enabled == 0 && existing.getAutoRedirect() != null && existing.getAutoRedirect() == 1) {
            update.setAutoRedirect(0);
        }
        authIdpConfigService.updateById(update);
        return ReqResult.success();
    }

    @Override
    public ReqResult<Void> setAutoRedirect(Long id, UserContext userContext) {
        log.info("[setAutoRedirect] Set auto redirect idp, id={}", id);
        if (id == null) {
            // 写操作必须带显式 WHERE：租户插件虽会补 _tenant_id，但 BlockAttack 防全表更新
            // 拦截先于租户改写执行，无 WHERE 的 SET-only wrapper 会被直接拒绝
            authIdpConfigService.update(new LambdaUpdateWrapper<AuthIdpConfig>()
                    .eq(AuthIdpConfig::getTenantId, userContext.getTenantId())
                    .set(AuthIdpConfig::getAutoRedirect, 0));
            return ReqResult.success();
        }
        AuthIdpConfig target = authIdpConfigService.getById(id);
        if (target == null) {
            return ReqResult.error("4000", "登录方式不存在");
        }
        if (target.getEnabled() == null || target.getEnabled() != 1) {
            return ReqResult.error("4000", "仅启用的登录方式可设为未登录自动跳转");
        }
        // 租户内全局唯一：先清后设
        authIdpConfigService.update(new LambdaUpdateWrapper<AuthIdpConfig>()
                .eq(AuthIdpConfig::getTenantId, userContext.getTenantId())
                .set(AuthIdpConfig::getAutoRedirect, 0));
        AuthIdpConfig update = new AuthIdpConfig();
        update.setId(id);
        update.setAutoRedirect(1);
        authIdpConfigService.updateById(update);
        return ReqResult.success();
    }

    // ==================== 配置构建与校验 ====================

    /**
     * 按类型把入参配置合并进实体（secret 空值/掩码时保留旧值），返回校验错误信息（null 表示通过）
     */
    private String applyConfig(AuthIdpConfig row, AuthIdpSaveDto dto, String oldConfigJson) {
        AuthIdpTypeEnum type = parseType(row.getType());
        switch (type) {
            case CAS:
                return applyCasConfig(row, dto.getCasConfig(), oldConfigJson);
            case OAUTH2:
                return applyOAuth2Config(row, dto.getOauth2Config(), oldConfigJson);
            case WECHAT:
                return applyWeChatConfig(row, dto.getWechatConfig(), oldConfigJson);
            default:
                return "登录方式类型无效";
        }
    }

    private String applyCasConfig(AuthIdpConfig row, AuthIdpSaveDto.CasConfigDto in, String oldConfigJson) {
        if (in == null) {
            // 更新时未传该类型配置段：仅改名称/图标等基本信息，配置保持不变
            return oldConfigJson == null ? "缺少 CAS 配置" : null;
        }
        if (StringUtils.isBlank(in.getServerUrl()) || !in.getServerUrl().trim().startsWith("http")) {
            return "CAS 服务地址必须以 http(s) 开头";
        }
        // 标准 CAS 协议无 client 凭证概念（客户端身份由 service 地址标识），
        // 需要 client 凭证的"CAS"应使用 OAUTH2/CUSTOM 类型对接其 OAuth 协议端点
        CasIdpConfig cfg = new CasIdpConfig();
        cfg.setServerUrl(StringUtils.removeEnd(in.getServerUrl().trim(), "/"));
        cfg.setFieldMapping(in.getFieldMapping());
        row.setConfig(JSON.toJSONString(cfg));
        return null;
    }

    private String applyOAuth2Config(AuthIdpConfig row, AuthIdpSaveDto.OAuth2ConfigDto in, String oldConfigJson) {
        if (in == null) {
            return oldConfigJson == null ? "缺少 OAuth2 配置" : null;
        }
        OAuth2ProviderEnum provider = OAuth2ProviderEnum.of(in.getProvider());
        if (provider == null) {
            return "OAuth2 提供方无效";
        }
        if (StringUtils.isBlank(in.getClientId())) {
            return "Client ID 不能为空";
        }
        OAuth2IdpConfig old = IdpConfigParser.parseOAuth2Json(oldConfigJson);
        OAuth2IdpConfig cfg = new OAuth2IdpConfig();
        cfg.setProvider(provider.getCode());
        cfg.setClientId(in.getClientId().trim());
        cfg.setClientSecret(mergeSecret(in.getClientSecret(), old.getClientSecret()));
        cfg.setAgentId(StringUtils.trimToNull(in.getAgentId()));
        if (provider == OAuth2ProviderEnum.WECOM && StringUtils.isBlank(cfg.getAgentId())) {
            return "企业微信登录必须配置 agentid";
        }
        if (provider == OAuth2ProviderEnum.CUSTOM) {
            if (StringUtils.isAnyBlank(in.getAuthorizeUrl(), in.getTokenUrl(), in.getUserinfoUrl())) {
                return "自定义提供方必须配置完整的授权/token/userinfo 端点";
            }
            if (StringUtils.isBlank(mergeSecret(in.getClientSecret(), old.getClientSecret()))) {
                return "Client Secret 不能为空";
            }
            cfg.setAuthorizeUrl(in.getAuthorizeUrl().trim());
            cfg.setTokenUrl(in.getTokenUrl().trim());
            cfg.setUserinfoUrl(in.getUserinfoUrl().trim());
            cfg.setScope(StringUtils.trimToNull(in.getScope()));
            cfg.setUsePkce(Boolean.TRUE.equals(in.getUsePkce()));
            cfg.setAuthMethod(StringUtils.defaultIfBlank(in.getAuthMethod(), "post"));
            cfg.setFieldMapping(in.getFieldMapping());
        }
        row.setConfig(JSON.toJSONString(cfg));
        return null;
    }

    private String applyWeChatConfig(AuthIdpConfig row, AuthIdpSaveDto.WeChatConfigDto in, String oldConfigJson) {
        if (in == null) {
            return oldConfigJson == null ? "缺少微信配置" : null;
        }
        WeChatIdpModeEnum mode = WeChatIdpModeEnum.of(in.getMode());
        if (mode == null) {
            return "微信应用形态无效（QRCODE-网站扫码 / OA-公众号网页授权）";
        }
        if (StringUtils.isBlank(in.getAppId())) {
            return "AppID 不能为空";
        }
        WeChatIdpConfig old = IdpConfigParser.parseWeChatJson(oldConfigJson);
        WeChatIdpConfig cfg = new WeChatIdpConfig();
        cfg.setMode(mode.getCode());
        cfg.setAppId(in.getAppId().trim());
        cfg.setAppSecret(mergeSecret(in.getAppSecret(), old.getAppSecret()));
        if (StringUtils.isBlank(cfg.getAppSecret())) {
            return "AppSecret 不能为空";
        }
        row.setConfig(JSON.toJSONString(cfg));
        return null;
    }

    /** 更新时 secret 传空或掩码表示保留旧值 */
    private static String mergeSecret(String newSecret, String oldSecret) {
        if (StringUtils.isBlank(newSecret) || SECRET_MASK.equals(newSecret)) {
            return StringUtils.trimToNull(oldSecret);
        }
        return newSecret.trim();
    }

    // ==================== 视图 ====================

    private AuthIdpVo toVo(AuthIdpConfig row) {
        AuthIdpTypeEnum type = parseType(row.getType());
        return AuthIdpVo.builder()
                .id(row.getId())
                .type(row.getType())
                .typeDesc(type == null ? row.getType() : type.getDesc())
                .name(row.getName())
                .icon(resolveIcon(row))
                .enabled(row.getEnabled())
                .autoRedirect(row.getAutoRedirect())
                .autoRegisterBind(row.getAutoRegisterBind())
                .sort(row.getSort())
                .config(maskConfig(row))
                .callbackUrl(siteBase() + CALLBACK_PATH + "?provider=" + row.getId())
                .build();
    }

    /** secret 字段替换为掩码后转 Map，供管理端回显 */
    private Map<String, Object> maskConfig(AuthIdpConfig row) {
        Object typed = null;
        AuthIdpTypeEnum type = parseType(row.getType());
        if (type == AuthIdpTypeEnum.CAS) {
            // CAS 无 secret 字段，直接回显
            typed = IdpConfigParser.parseCas(row);
        } else if (type == AuthIdpTypeEnum.OAUTH2) {
            OAuth2IdpConfig cfg = IdpConfigParser.parseOAuth2(row);
            if (cfg.getClientSecret() != null) {
                cfg.setClientSecret(SECRET_MASK);
            }
            typed = cfg;
        } else if (type == AuthIdpTypeEnum.WECHAT) {
            WeChatIdpConfig cfg = IdpConfigParser.parseWeChat(row);
            if (cfg.getAppSecret() != null) {
                cfg.setAppSecret(SECRET_MASK);
            }
            typed = cfg;
        }
        if (typed == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = JSON.parseObject(JSON.toJSONString(typed));
        return map == null ? new LinkedHashMap<>() : map;
    }

    /**
     * 租户站点地址：优先取配置值（AuthInterceptor 会按 Referer 改写 siteUrl，
     * 带外部 Referer 的请求里 siteUrl 可能指向他站，siteConfigUrl 才是本站地址）
     */
    private String siteBase() {
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig == null) {
            return "";
        }
        String base = StringUtils.defaultIfBlank(tenantConfig.getSiteConfigUrl(), tenantConfig.getSiteUrl());
        return StringUtils.removeEnd(StringUtils.trimToEmpty(base), "/");
    }

    /** 图标三级兜底：配置 icon → 内置品牌图标 → 平台字母图标服务（与登录页列表同口径） */
    private String resolveIcon(AuthIdpConfig config) {
        String icon = idpBrandIcon.resolve(config);
        if (StringUtils.isNotBlank(icon)) {
            return icon;
        }
        try {
            return com.xspaceagi.system.application.util.DefaultIconUrlUtil
                    .setDefaultIconUrl(null, config.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private static AuthIdpTypeEnum parseType(String type) {
        return AuthIdpTypeEnum.of(type);
    }

    /** 必填校验：null 或空白串抛 BizException */
    private static void requireField(Object value, String fieldName) {
        if (value == null || (value instanceof String str && StringUtils.isBlank(str))) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, fieldName);
        }
    }
}
