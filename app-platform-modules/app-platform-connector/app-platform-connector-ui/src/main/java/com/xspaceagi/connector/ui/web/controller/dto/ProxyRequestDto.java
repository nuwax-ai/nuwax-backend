package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 通用代理请求（路径 C）。客户端自带鉴权头一律不转发，凭证由模块内注入。
 */
@Data
@Schema(description = "通用代理请求")
public class ProxyRequestDto {

    @Size(max = 16, message = "长度不能超过 16")
    @NotBlank(message = "必填")
    @Schema(description = "GET/POST/PUT/PATCH/DELETE")
    private String method;

    @Size(max = 2048, message = "长度不能超过 2048")
    @NotBlank(message = "必填")
    @Schema(description = "相对 base_url 的路径，/ 开头，禁 ..")
    private String path;

    @Schema(description = "查询参数")
    private Map<String, String> query;

    @Schema(description = "请求体（原样转发）")
    private String body;

    @Schema(description = "指定连接 id，可空")
    private Long connectionId;
}
