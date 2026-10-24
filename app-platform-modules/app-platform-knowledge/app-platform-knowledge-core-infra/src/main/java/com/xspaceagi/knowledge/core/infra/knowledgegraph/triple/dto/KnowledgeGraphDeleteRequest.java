package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 删除知识图谱请求
 *
 * @author system
 * @date 2026-04-16
 */
@Data
@Schema(description = "删除知识图谱请求")
public class KnowledgeGraphDeleteRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long kbId;

    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long documentId;
}
