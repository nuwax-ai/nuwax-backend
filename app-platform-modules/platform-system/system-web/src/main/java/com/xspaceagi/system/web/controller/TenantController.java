package com.xspaceagi.system.web.controller;

import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "租户信息查询接口")
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    @Value("${app.version:1.0.0}")
    private String version;

    @Resource
    private IFileAccessService iFileAccessService;

    @Value("${supportCustomDomain:false}")
    private String supportCustomDomain;

    @Value("${spring.servlet.multipart.max-file-size:100MB}")
    private String maxFileSize;

    @Value("${license:}")
    private String license;

    @Value("${model-api-proxy.base-api-url:}")
    private String baseApiUrl;

    @Operation(summary = "租户配置信息查询接口")
    @RequestMapping(path = "/config", method = RequestMethod.GET)
    public ReqResult<TenantConfigDto> getConfig() {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        tenantConfigDto.setDefaultAgentIds(null);
        tenantConfigDto.setDomainNames(null);
        tenantConfigDto.setDefaultSuggestModelId(null);
        tenantConfigDto.setDefaultSummaryModelId(null);
        tenantConfigDto.setCodeSafeCheckPrompt(null);
        tenantConfigDto.setGlobalSystemPrompt(null);
        tenantConfigDto.setUserWhiteList(null);
        tenantConfigDto.setSmtpHost(null);
        tenantConfigDto.setSmtpPassword(null);
        tenantConfigDto.setSmtpUsername(null);
        tenantConfigDto.setSmtpPort(null);
        tenantConfigDto.setSmsAccessKeyId(null);
        tenantConfigDto.setSmsAccessKeySecret(null);
        tenantConfigDto.setSmsSignName(null);
        tenantConfigDto.setSmsTemplateCode(null);
        tenantConfigDto.setCaptchaAccessKeyId(null);
        tenantConfigDto.setCaptchaAccessKeySecret(null);
        tenantConfigDto.setMpAppId(null);
        tenantConfigDto.setMpAppSecret(null);
        tenantConfigDto.setSandboxConfig(null);
        tenantConfigDto.setVersion(version);
        tenantConfigDto.setSupportCustomDomain(StringUtils.equals(supportCustomDomain, "true"));
        tenantConfigDto.setEnabledSandbox(true);
        tenantConfigDto.setSiteLogo(iFileAccessService.getFileUrlWithAk(tenantConfigDto.getSiteLogo(), true));
        tenantConfigDto.setFaviconUrl(iFileAccessService.getFileUrlWithAk(tenantConfigDto.getFaviconUrl(), true));
        tenantConfigDto.setSquareBanner(iFileAccessService.getFileUrlWithAk(tenantConfigDto.getSquareBanner(), true));
        tenantConfigDto.setMaxFileSize(maxFileSize);
        if (StringUtils.isBlank(tenantConfigDto.getTemplateConfig()) || tenantConfigDto.getTemplateConfig().equals("{}")) {
            tenantConfigDto.setTemplateConfig("{\"primaryColor\":\"#5147ff\",\"backgroundId\":\"bg-variant-1\",\"antdTheme\":\"light\",\"layoutStyle\":\"light\",\"navigationStyle\":\"style1\",\"timestamp\":1757425328082}");
        }
        String userName = "";
        if (RequestContext.get().isLogin()) {
            UserDto user = (UserDto) RequestContext.get().getUser();
            if (StringUtils.isNotBlank(user.getNickName())) {
                userName = user.getNickName();
            } else {
                userName = user.getUserName();
            }
        }
        if (StringUtils.isBlank(tenantConfigDto.getHomeSlogan())) {
            tenantConfigDto.setHomeSlogan("Hi {{USER_NAME}}, how can I help you today?");
        }
        if (StringUtils.isNotBlank(tenantConfigDto.getHomeSlogan())) {
            tenantConfigDto.setHomeSlogan(tenantConfigDto.getHomeSlogan().replace("{{USER_NAME}}", userName));
        }
        if (StringUtils.isBlank(tenantConfigDto.getLoginPageText())) {
            tenantConfigDto.setLoginPageText("Easily build and deploy your<br>private Agentic AI solution");
        }
        if (StringUtils.isBlank(tenantConfigDto.getLoginPageSubText())) {
            tenantConfigDto.setLoginPageSubText("Comprehensive workflow and plugin development, RAG knowledge base and data table storage, MCP integration and open capabilities");
        }
        if (StringUtils.isNotBlank(license)) {
            tenantConfigDto.setCommercialEdition(true);
        }

        String siteUrl = tenantConfigDto.getSiteUrl();
        if (StringUtils.isNotBlank(baseApiUrl)) {
            siteUrl = baseApiUrl;
        }
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        tenantConfigDto.setBaseModelApiUrl(siteUrl + "/api/proxy/model");
        I18nUtil.replaceSystemMessage(tenantConfigDto);
        return ReqResult.success(tenantConfigDto);
    }
}