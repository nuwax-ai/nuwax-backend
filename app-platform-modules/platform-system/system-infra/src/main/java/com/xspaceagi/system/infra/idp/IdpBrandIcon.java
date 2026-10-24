package com.xspaceagi.system.infra.idp;

import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.enums.OAuth2ProviderEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录方式图标解析：管理端配置 icon 优先，未配置时按返回内置品牌图标 URL
 */
@Component
public class IdpBrandIcon {

    @Value("${auth-idp.brand-icons.github:}")
    private String github;

    @Value("${auth-idp.brand-icons.wechat:}")
    private String wechat;

    @Value("${auth-idp.brand-icons.feishu:}")
    private String feishu;

    @Value("${auth-idp.brand-icons.dingtalk:}")
    private String dingtalk;

    @Value("${auth-idp.brand-icons.wecom:}")
    private String wecom;

    /**
     * @return 管理端配置的图标 URL；或内置品牌图标 URL；无匹配返回 null
     */
    public String resolve(AuthIdpConfig config) {
        if (config == null) {
            return null;
        }
        if (StringUtils.isNotBlank(config.getIcon())) {
            return config.getIcon();
        }
        String url = brandUrl(config);
        return StringUtils.isBlank(url) ? null : url;
    }

    private String brandUrl(AuthIdpConfig config) {
        if (AuthIdpTypeEnum.WECHAT.getCode().equals(config.getType())) {
            return wechat;
        }
        if (AuthIdpTypeEnum.OAUTH2.getCode().equals(config.getType())) {
            OAuth2ProviderEnum provider = OAuth2ProviderEnum.of(IdpConfigParser.parseOAuth2(config).getProvider());
            if (provider == OAuth2ProviderEnum.FEISHU) {
                return feishu;
            }
            if (provider == OAuth2ProviderEnum.WECOM) {
                return wecom;
            }
            if (provider == OAuth2ProviderEnum.DINGTALK) {
                return dingtalk;
            }
            if (provider == OAuth2ProviderEnum.GITHUB) {
                return github;
            }
        }
        return null;
    }
}
