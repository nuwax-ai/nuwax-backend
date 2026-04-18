package com.xspaceagi.knowledge.sdk.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库配置创建请求
 */
@Builder
@Getter
@Setter
public class KnowledgeCreateRequestVo {


    /**
     * 用户ID
     */
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @Schema(description = "知识库名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base name is required")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    /**
     * 数据类型,默认文本,1:文本;2:表格
     */
    @Schema(description = "数据类型,默认文本,1:文本;2:表格", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer dataType;


    @Schema(description = "图标的url地址,[可选]", requiredMode = Schema.RequiredMode.REQUIRED)
    private String icon;

}
