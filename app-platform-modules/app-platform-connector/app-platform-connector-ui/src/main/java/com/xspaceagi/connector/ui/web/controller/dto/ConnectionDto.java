package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 连接视图：只含 profile，绝不含凭证（凭证边界铁律）。
 */
@Data
@Schema(description = "用户连接视图（仅 profile，无凭证）")
public class ConnectionDto {

    private Long id;

    private Long spaceId;

    private String providerService;

    private String providerDisplayName;

    private String name;

    /** 凭证健康度：active/expired/error。由系统写，不表达用户意图 */
    private String status;

    /** 用户启用开关。由用户控制，与 status 正交；两者同时成立才可被执行解析选中 */
    private Boolean enabled;

    /** accountId/displayName/avatar，唯一可暴露给 LLM 的信息 */
    private Object profile;

    /**
     * 令牌身份：{@code tenant}=应用身份（如飞书 tenant_access_token）、{@code user}=用户身份。
     * <p>
     * 同一个 provider 下两种身份会各占一条连接（扫码自注册产出应用身份，之后再次扫码做用户授权
     * 产出用户身份），而两条连接的 name 完全相同。没有这个字段，界面只能显示成一模一样的两行
     * 「已连接」，用户既分不清谁是谁、也解释不了「怎么多了一条」。
     * <p>
     * 存量连接建连时还没写这个键，读出来是 {@code null}；调用方按「未知」处理，不要当成 user。
     */
    private String identity;

    private List<String> grantedScopes;

    private Date modified;

    private Date created;
}
