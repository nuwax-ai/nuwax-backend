package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class PluginAddDto implements Serializable {

    @Schema(description =  "空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Space ID is required")
    private Long spaceId;

    @Schema(description =  "创建人ID")
    private Long creatorId;

    @Schema(description =  "插件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Plugin name is required")
    private String name;

    @Schema(description =  "插件描述")
    private String description;

    @Schema(description =  "插件图标")
    private String icon;

    @Schema(description =  "插件类型")
    private PluginTypeEnum type;

    @Schema(description =  "插件代码语言")
    private CodeLanguageEnum codeLang;
}