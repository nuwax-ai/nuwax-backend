package com.xspaceagi.userapp.web.dto;

import java.util.List;

import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存三方应用 OAuth2 接入设置
 */
@Data
@Schema(description = "保存三方应用 OAuth2 接入设置")
public class ThirdAppOAuthSettingReq {
    private ProjectTypeEnum projectType;
    @NotNull
    @Schema(description = "统一项目 ID（user_project 主键）")
    private Long projectId;

    @Schema(description = "应用主页地址；留空表示不修改")
    private String homepageUrl;

    @Schema(description = "OAuth2 主回调地址；留空表示不修改。沙箱（AI）调用时只在库中为空的情况下可写入一次，"
            + "此后由网页端人工维护，AI 自己的回调地址请加到补充列表")
    private String redirectUri;

    @Schema(description = "要追加的 OAuth2 补充回调地址（不含主地址）；null 表示不追加；"
            + "逐条须为绝对 http(s) 地址，最多 20 条、单条不超过 512 字符；与主地址或已有条目重复的自动丢弃（幂等）。"
            + "新增条目会记下写入来源，供删除权限判定与审计")
    private List<String> addRedirectUris;

    @Schema(description = "要删除的 OAuth2 补充回调地址（按地址全等匹配）；null 表示不删除；删除不存在的地址是空操作。"
            + "沙箱（AI）来源只能删自己加的条目，删到网页人工配置的条目会被跳过，跳过项回报在响应的 "
            + "skippedRedirectUriRemovals 里（其余部分照常生效，不报错）；网页端人工调用不受此限制")
    private List<String> removeRedirectUris;

    @Schema(description = "申请的 scope 范围；null 表示不修改，空数组表示申请清空并回落平台默认（profile）；提交后待管理员审核，通过前项目按原范围生效")
    private List<String> scopes;
}
