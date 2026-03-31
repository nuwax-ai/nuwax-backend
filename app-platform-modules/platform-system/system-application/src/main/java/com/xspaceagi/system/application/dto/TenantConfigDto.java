package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class TenantConfigDto implements Serializable {

    @Schema(description = "租户ID", hidden = true)
    private Long tenantId;

    @Schema(description = "站点名称")
    private String siteName;

    private String siteUrl;

    @Schema(description = "站点配置地址", hidden = true)
    private String siteConfigUrl;

    @Schema(description = "站点描述")
    private String siteDescription;

    @Schema(description = "站点LOGO，为空使用现有默认的")
    private String siteLogo;

    private String faviconUrl;

    @Schema(description = "登录页文案")
    private String loginPageText;

    @Schema(description = "登录页副文案")
    private String loginPageSubText;

    @Schema(description = "广场Banner地址，为空使用现有默认的")
    private String squareBanner;

    @Schema(description = "广场Banner文案标题")
    private String squareBannerText;

    @Schema(description = "广场Banner文案副标题")
    private String squareBannerSubText;

    @Schema(description = "广场Banner链接，如果链接不为空，点击跳转")
    private String squareBannerLinkUrl;

    @Schema(description = "开启注册, 0 关闭；1 开启，如果为0，前端不展示注册入口")
    private Integer openRegister;

    @Schema(description = "默认会话总结模型", hidden = true)
    private Long defaultSummaryModelId;

    @Schema(description = "默认问题建议模型", hidden = true)
    private Long defaultSuggestModelId;

    @Schema(description = "默认会话模型")
    private Long defaultChatModelId;

    @Schema(description = "默认嵌入模型")
    private Long defaultEmbedModelId;

    @Schema(description = "默认知识库模型")
    private Long defaultKnowledgeModelId;

    @Schema(description = "默认站点问答型Agent")
    private Long defaultAgentId;

    @Schema(description = "默认站点任务型Agent")
    private Long defaultTaskAgentId;

    @Schema(description = "首页会话框下的推荐问题")
    private List<String> homeRecommendQuestions;

    @Schema(description = "默认站点Agent集群", hidden = true)
    private List<Long> defaultAgentIds;

    @Schema(description = "推荐Agent列表", hidden = true)
    private List<Long> recommendAgentIds;

    @Schema(description = "官方智能体配置")
    private List<Long> officialAgentIds;

    @Schema(description = "官方用户名")
    private String officialUserName;

    @Schema(description = "站点域名")
    private List<String> domainNames;

    @Schema(description = "最大上传文件大小，例如 100MB")
    private String maxFileSize;

    private String casClientHostUrl;
    private String casValidateUrl;
    private String casLoginUrl;
    private Integer authType;
    private Integer agentPublishAudit;
    private Integer pluginPublishAudit;
    private Integer workflowPublishAudit;
    private Integer skillPublishAudit;

    private List<String> userWhiteList;
    private String codeSafeCheckPrompt;
    private Integer openCodeSafeCheck;
    private String globalSystemPrompt;

    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpPassword;

    private String smsAccessKeyId;
    private String smsAccessKeySecret;
    private String smsSignName;
    private String smsTemplateCode;

    private Long authExpire;

    private Integer openCaptcha;
    private String captchaAccessKeyId;
    private String captchaAccessKeySecret;
    private String captchaPrefix;
    private String captchaSceneId;
    private String pageFooterText;

    private Integer allowAgentTempChat;
    private Integer allowAgentApi;
    private Integer allowMcpExport;
    private String version;

    private String templateConfig;
    private String homeSlogan;

    @Schema(description = "默认编码模型")
    private Long defaultCodingModelId;

    @Schema(description = "默认视觉模型")
    private Long defaultVisualModelId;

    private String mpAppId;
    private String mpAppSecret;
    private String sandboxConfig;
    private boolean enabledSandbox;
    private Boolean supportCustomDomain;
    private String userComputerDefaultSkillIds;
    private String officialPluginIds;
    private String officialWorkflowIds;
    private String officialSkillIds;

    public enum AuthTypeEnum {
        PHONE(1, "手机"),
        CAS(2, "CAS"),
        EMAIL(3, "邮箱");
        private int code;
        private String desc;

        AuthTypeEnum(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() {
            return code;
        }
    }
}
