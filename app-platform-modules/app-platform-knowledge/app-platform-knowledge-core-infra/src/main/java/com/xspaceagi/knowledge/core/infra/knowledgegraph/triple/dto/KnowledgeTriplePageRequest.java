package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识库三元组分页查询请求
 *
 * @author system
 * @date 2026-04-22
 */
@Data
@Schema(description = "知识库三元组分页查询请求")
public class KnowledgeTriplePageRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long knowledgeId;

    @Schema(description = "页码，从1开始", example = "1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，默认10", example = "10")
    private Integer pageSize = 10;
}
