package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class UserConfigSetDto {

    /**
     * 不做 Bean Validation：key 的格式与长度统一在应用层校验，保证空 key / 非法 key 返回同一个业务错误码
     */
    @Schema(description = "配置项 key（1-128 位，仅允许字母、数字、下划线、点、冒号、短横线）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String key;

    @Schema(description = "配置项值（任意 JSON：标量/对象/数组，序列化后最大 64KB）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Object value;

    @Schema(description = "备注（最大 255 字符，传 null 表示清空）")
    private String description;
}
