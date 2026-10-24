package com.xspaceagi.userapp.application.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.application.oauth2.OAuth2GrantContext;
import com.xspaceagi.userapp.application.oauth2.OAuth2ScopeRegistry;
import com.xspaceagi.userapp.application.service.IOAuth2AuthorizationService;
import com.xspaceagi.userapp.application.service.IThirdAppOAuth2ApplicationService;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.sdk.IOAuth2TokenRpcService;
import com.xspaceagi.userapp.sdk.dto.OAuth2TokenContextDto;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * access_token 校验入口：scope 白名单判定集中在此，拦截器保持极薄。
 */
@Slf4j
@Service
public class OAuth2TokenRpcServiceImpl implements IOAuth2TokenRpcService {

    @Resource
    private IOAuth2AuthorizationService oAuth2AuthorizationService;

    @Resource
    private IThirdAppOAuth2ApplicationService thirdAppOAuth2ApplicationService;

    @Override
    public OAuth2TokenContextDto validateAccessToken(String accessToken, String requestUri) {
        if (!oAuth2AuthorizationService.isAccessToken(accessToken)) {
            return null;
        }
        OAuth2GrantContext context = oAuth2AuthorizationService.loadAccessToken(accessToken);
        if (context == null) {
            return null;
        }
        // 用户级撤销：签发时间早于撤销时间的令牌一律失效
        Long revokedAt = oAuth2AuthorizationService.revokedAt(context.getClientId(), context.getUserId());
        if (revokedAt != null && context.getIssuedAt() != null && context.getIssuedAt() <= revokedAt) {
            return null;
        }
        // 客户端被删除 / 停用 / 凭证重生成（client_id 变更）后，旧令牌随之失效
        UserProjectModel project = thirdAppOAuth2ApplicationService.findEnabledClient(context.getClientId());
        if (project == null) {
            return null;
        }
        // 管理员收窄应用允许范围后，已签发令牌同步收窄
        List<String> scopes = new ArrayList<>(OAuth2ScopeRegistry.parse(context.getScope()));
        scopes.retainAll(thirdAppOAuth2ApplicationService.resolveScopes(project));
        // 回传的是收窄后的有效 scope，而不是签发时的原始值：端点鉴权与字段级权限都必须以它为准，
        // 否则管理员撤销某个 scope 后，已签发令牌在字段级判定上仍会被当成拥有该权限。
        if (!OAuth2ScopeRegistry.allows(scopes, requestUri)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.oauth2InsufficientScope);
        }
        OAuth2TokenContextDto dto = new OAuth2TokenContextDto();
        dto.setProjectId(context.getProjectId());
        dto.setClientId(context.getClientId());
        dto.setUserId(context.getUserId());
        dto.setTenantId(context.getTenantId());
        dto.setScope(String.join(",", scopes));
        dto.setGrantType(context.getGrantType());
        return dto;
    }
}
