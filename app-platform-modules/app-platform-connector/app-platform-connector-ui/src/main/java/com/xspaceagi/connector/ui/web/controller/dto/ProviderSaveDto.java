package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建/编辑 provider 请求体（用户侧仅声明式，§4.8）。
 * <p>
 * 长度上限与 connector_provider 列宽一一对应（service 64 / display_name 128 /
 * description 512 / icon 255 / category 64 / auth_type 32 / base_url 512 /
 * oauth_app_mode 20），在入口拦住超长值，避免落到 DB 才报 data too long。
 * <p>
 * 本 DTO 同时服务创建与编辑，字段一律可空：编辑是「部分更新」，未传的字段沿用旧值，
 * 因此这里只做长度约束；必填由服务层按场景判定（新建要求 service/displayName/authType）。
 */
@Data
@Schema(description = "创建/编辑 provider 请求")
public class ProviderSaveDto {

    @Size(max = 64, message = "长度不能超过 64")
    @Schema(description = "创建时必填：唯一标识，小写字母开头 [a-z0-9_]")
    private String service;

    @Schema(description = "创建时所在空间（创建时必填）")
    private Long spaceId;

    @Size(max = 128, message = "长度不能超过 128")
    @Schema(description = "创建时必填；编辑时不传即保持原值")
    private String displayName;

    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "连接器介绍（可选）")
    private String description;

    @Size(max = 255, message = "长度不能超过 255")
    private String icon;

    @Size(max = 64, message = "长度不能超过 64")
    private String category;

    private List<String> tags;

    @Size(max = 32, message = "长度不能超过 32")
    @Schema(description = "no_auth/api_key/bearer/oauth2/oauth2_device/custom；"
            + "oauth2_device 为扫码建连（设备码流程），其 oauth-config 的 authUrl 填设备授权端点、无需 redirect_uri")
    private String authType;

    @Size(max = 512, message = "长度不能超过 512")
    private String baseUrl;

    @Schema(description = "key 注入位置等：{\"keyName\":\"Authorization\",\"injectTo\":\"header\",\"prefix\":\"Bearer \"}")
    private Object authConfig;

    @Size(max = 20, message = "长度不能超过 20")
    @Schema(description = "OAuth App 模式：platform=全局公共App（默认）/ byo=每个用户连接时自填")
    private String oauthAppMode;

    private Boolean proxyEnabled;
}
