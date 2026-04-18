package com.xspaceagi.knowledge.man.ui.web.dto.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库基础配置-列表查询")
@Getter
@Setter
public class KnowledgeConfigQueryRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @Schema(description = "知识库名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base name is required")
    private String name;

    /**
     * 数据类型,默认文本,1:文本;2:表格
     */
    @Schema(description = "数据类型,默认文本,1:文本;2:表格", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer dataType;



}
