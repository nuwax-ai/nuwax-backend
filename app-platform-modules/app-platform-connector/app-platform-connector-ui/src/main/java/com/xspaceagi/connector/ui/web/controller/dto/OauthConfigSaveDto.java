package com.xspaceagi.connector.ui.web.controller.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * OAuth App 配置保存请求。
 * <p>
 * 本 DTO 同时服务「首次配置」与「更新」，除 service（定位用）外字段一律可空：
 * 更新是<b>部分更新</b>，未传的字段沿用旧值（clientId/authUrl/tokenUrl/scopes/clientSecret 同理），
 * 否则「只想换个 secret」也得把 clientId 与两个端点原样回传一遍。必填由服务层按场景判定
 * （仅首次配置要求 clientId/authUrl/tokenUrl/clientSecret 齐全）。
 * <p>
 * 长度上限与 connector_oauth_config 列宽对应（provider_service 64 /
 * client_id 255 / auth_url 512 / token_url 512）；client_secret 密文落 text 列，
 * 明文取 1024 保守上限。
 */
@Data
@Schema(description = "OAuth App 配置保存请求")
public class OauthConfigSaveDto {

    @Size(max = 64, message = "长度不能超过 64")
    @NotBlank(message = "必填")
    private String service;

    @Size(max = 255, message = "长度不能超过 255")
    @Schema(description = "首次必填；更新时不传即沿用")
    private String clientId;

    /**
     * 明文仅存在于请求入参 → 模块内加密落库，任何序列化出口一律不带出：
     * 全局 HTTP 日志拦截器用 fastjson2 序列化请求体，serialize=false 使其不入日志；
     * Jackson 侧 WRITE_ONLY 同等兜底。绑定（setter）不受影响。
     */
    @Size(max = 1024, message = "长度不能超过 1024")
    @Schema(description = "client_secret 明文（只在模块内加密落库，不出边界）；首次必填，更新时留空=沿用")
    @JSONField(serialize = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String clientSecret;

    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "首次必填；更新时不传即沿用")
    private String authUrl;

    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "首次必填；更新时不传即沿用")
    private String tokenUrl;

    @Schema(description = "申请的 scope 列表")
    private List<String> scopes;
}
