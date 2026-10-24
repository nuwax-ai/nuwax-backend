package com.xspaceagi.connector.ui.web.controller.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 直填凭证建连接请求（api_key/bearer/custom）。
 * <p>
 * 长度上限与 connector_connection 列宽对应（provider_service 64 / name 64）。
 */
@Data
@Schema(description = "直填凭证建连接请求")
public class CreateConnectionDto {

    /**
     * 可不传：官方连接器无空间归属，空间自建连接器的归属空间由 provider 推导。
     * 传了则必须与 provider 的归属空间一致，否则报 VALIDATION_ERROR。
     */
    @Schema(description = "所属空间 ID；可不传（以连接器归属空间为准），传了须与之一致")
    private Long spaceId;

    @Size(max = 64, message = "长度不能超过 64")
    @NotBlank(message = "必填")
    private String providerService;

    @Size(max = 64, message = "长度不能超过 64")
    @Schema(description = "连接别名，缺省用 provider 展示名")
    private String name;

    /**
     * 凭证明文只进不出：全局 HTTP 日志拦截器用 fastjson2 序列化请求体，
     * serialize=false 使凭证字段不入日志（Jackson 侧 WRITE_ONLY 同等兜底）。绑定不受影响。
     */
    @Schema(description = "凭证字段：api_key 填 {\"<keyName>\":\"...\"}；bearer 填 {\"token\":\"...\"}；custom 任意多字段")
    @JSONField(serialize = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Map<String, String> fields;
}
