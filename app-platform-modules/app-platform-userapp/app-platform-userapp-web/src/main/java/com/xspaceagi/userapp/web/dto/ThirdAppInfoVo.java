package com.xspaceagi.userapp.web.dto;

import java.util.Date;
import java.util.List;

import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 三方应用基本信息 + OAuth2 接入摘要（设置页「基本信息」卡片展示）。
 * <p>
 * 与 {@link ThirdAppOAuthSettingVo} 的分工：后者只表达「认证信息」卡片，本 VO 是应用自身的详情。
 * 密钥明文一律不下发，只给 {@code hasClientSecret} 布尔位（明文需调 /secret/{projectId} 单独获取）。
 */
@Data
@Schema(description = "三方应用信息")
public class ThirdAppInfoVo {

    @Schema(description = "统一项目 ID（user_project 主键）")
    private Long projectId;

    @Schema(description = "项目类型，取值 ThirdApp=三方应用、UserApp=全栈应用（后者也走 OAuth2 接入）")
    private String projectType;

    @Schema(description = "应用名称")
    private String name;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用图标")
    private String icon;

    @Schema(description = "所属空间 ID")
    private Long spaceId;

    @Schema(description = "创建者用户 ID")
    private Long creatorId;

    @Schema(description = "应用主页地址")
    private String homepageUrl;

    @Schema(description = "OAuth2 主回调地址")
    private String redirectUri;

    @Schema(description = "OAuth2 补充回调地址（不含主地址；生效集合 = 主地址 ∪ 各条目的 uri，未配置为空数组）。"
            + "每条带 source（Manual=网页人工配置 / Sandbox=沙箱里的 AI 添加）、createdBy、createdAt")
    private List<RedirectUriEntry> redirectUris;

    @Schema(description = "OAuth2 Client ID")
    private String clientId;

    @Schema(description = "是否已生成 Client Secret（明文需调 /secret/{projectId} 单独获取）")
    private Boolean hasClientSecret;

    @Schema(description = "本应用允许申请的 scope")
    private List<String> scopes;

    @Schema(description = "OAuth2 接入是否启用")
    private Boolean enabled;

    @Schema(description = "发布状态")
    private String publishStatus;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
