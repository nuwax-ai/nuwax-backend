package com.xspaceagi.userapp.web.dto;

import java.util.List;

import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 三方应用 OAuth2 认证信息（设置页展示；不含密钥明文）
 */
@Data
@Schema(description = "三方应用 OAuth2 认证信息")
public class ThirdAppOAuthSettingVo {

    @Schema(description = "统一项目 ID（user_project 主键，创建接口据此回传新应用 id）")
    private Long projectId;

    @Schema(description = "OAuth2 Client ID")
    private String clientId;

    @Schema(description = "是否已生成 Client Secret（明文需调 /secret/{projectId} 单独获取）")
    private Boolean hasClientSecret;

    @Schema(description = "应用主页地址")
    private String homepageUrl;

    @Schema(description = "OAuth2 主回调地址")
    private String redirectUri;

    @Schema(description = "OAuth2 补充回调地址（不含主地址；生效集合 = 主地址 ∪ 各条目的 uri，未配置为空数组）。"
            + "每条带 source（Manual=网页人工配置 / Sandbox=沙箱里的 AI 添加）、createdBy、createdAt，"
            + "前端据此展示来源并决定删除入口是否可用")
    private List<RedirectUriEntry> redirectUris;

    @Schema(description = "本次保存中「请求删除但未删除」的补充回调地址（沙箱来源只能删自己添加的条目）。"
            + "仅 /setting/save 返回，其余接口为 null；其余改动照常生效，不是整次失败")
    private List<String> skippedRedirectUriRemovals;

    @Schema(description = "本应用允许申请的 scope（当前已生效值）")
    private List<String> scopes;

    @Schema(description = "scope 变更审核状态（最近一条申请：Pending 待审核 / Approved 已通过 / Rejected 已拒绝；从未申请为 null）")
    private String scopeApplyStatus;

    @Schema(description = "待审核的目标 scope（仅审核状态为 Pending 时返回；空数组表示申请清空回落平台默认）")
    private List<String> pendingScopes;

    @Schema(description = "最近一次 scope 申请的拒绝原因（仅审核状态为 Rejected 时返回）")
    private String scopeRejectReason;

    @Schema(description = "OAuth2 接入是否启用")
    private Boolean enabled;
}
