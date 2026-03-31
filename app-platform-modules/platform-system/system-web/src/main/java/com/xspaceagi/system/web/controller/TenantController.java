package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.FileAkUtil;
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
    private FileAkUtil fileAkUtil;

    @Value("${supportCustomDomain:false}")
    private String supportCustomDomain;

    @Value("${spring.servlet.multipart.max-file-size:100MB}")
    private String maxFileSize;

    @Operation(summary = "租户配置信息查询接口")
    @RequestMapping(path = "/config", method = RequestMethod.GET)
    public ReqResult<TenantConfigDto> getConfig() {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        tenantConfigDto.setDefaultAgentIds(null);
        tenantConfigDto.setDomainNames(null);
        tenantConfigDto.setDefaultSuggestModelId(null);
        tenantConfigDto.setDefaultSummaryModelId(null);
        tenantConfigDto.setRecommendAgentIds(null);
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
        tenantConfigDto.setSiteLogo(fileAkUtil.getFileUrlWithAk(tenantConfigDto.getSiteLogo()));
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
            tenantConfigDto.setHomeSlogan("嗨 {{USER_NAME}}，有什么我可以帮忙的吗？");
        }
        if (StringUtils.isNotBlank(tenantConfigDto.getHomeSlogan())) {
            tenantConfigDto.setHomeSlogan(tenantConfigDto.getHomeSlogan().replace("{{USER_NAME}}", userName));
        }
        if (StringUtils.isBlank(tenantConfigDto.getLoginPageText())) {
            tenantConfigDto.setLoginPageText("轻松构建部署您<br>私有的 Agentic AI 解决方案");
        }
        if (StringUtils.isBlank(tenantConfigDto.getLoginPageSubText())) {
            tenantConfigDto.setLoginPageSubText("提供完善的工作流、插件开发能力，RAG 知识库与数据表存储能力，MCP接入以及开放能力");
        }
        return ReqResult.success(tenantConfigDto);
    }
}