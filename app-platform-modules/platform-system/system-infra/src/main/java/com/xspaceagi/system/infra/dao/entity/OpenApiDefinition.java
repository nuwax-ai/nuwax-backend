package com.xspaceagi.system.infra.dao.entity;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@I18n(module = "OpenApi")
@Data
public class OpenApiDefinition {
    private String name;
    @I18nField(keyPrefix = true)
    private String key;
    private User.Role role;
    private String path;
    private List<OpenApiDefinition> apiList;
    @Schema(description = "每分钟请求次数，-1 表示不限制")
    private Integer rpm;
    @Schema(description = "每天请求次数，-1 表示不限制")
    private Integer rpd;
}
