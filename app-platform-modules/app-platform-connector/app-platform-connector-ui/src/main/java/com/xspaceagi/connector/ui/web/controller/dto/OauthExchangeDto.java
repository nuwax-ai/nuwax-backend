package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * OAuth 授权回调提交（JSON 版，供 Agent/脚本调用）。
 * <p>
 * 与浏览器回调 {@code GET /connector/oauth/callback} 是同一套处理逻辑的两副面孔：
 * 后者必须回 HTML 完成页（人要看的），前者回结构化 JSON（程序要判的）。
 * <p>
 * 不接收 service：{@code state} 已经唯一绑定连接器与用户，再传一个 service 只会引入
 * 「两者不一致时听谁的」这种歧义；返回体里带 service 供调用方核对。
 */
@Data
@Schema(description = "OAuth 授权回调提交（JSON）")
public class OauthExchangeDto {

    @NotBlank(message = "必填")
    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "发起授权时拿到的 state（15 分钟有效、一次性）")
    private String state;

    @Size(max = 2048, message = "长度不能超过 2048")
    @Schema(description = "IdP 回调带回的 code；用户拒绝授权时可不传，改传 error")
    private String code;

    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "IdP 回调带回的 error（用户拒绝授权时）")
    private String error;
}
