package com.xspaceagi.knowledge.domain.knowledgegraph.triple.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识图谱生成请求
 *
 * @author system
 * @date 2026-04-16
 */
@Data
@Schema(description = "知识图谱生成请求")
public class KnowledgeGraphGenerateRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long knowledgeId;

    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long documentId;
}
