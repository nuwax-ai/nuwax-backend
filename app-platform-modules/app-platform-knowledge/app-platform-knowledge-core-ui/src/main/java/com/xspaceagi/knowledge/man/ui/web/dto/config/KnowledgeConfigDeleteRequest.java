package com.xspaceagi.knowledge.man.ui.web.dto.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "删除请求参数")
public class KnowledgeConfigDeleteRequest implements Serializable {


    @Schema(description = "数据ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Data ID is required")
    private Long id;

}
