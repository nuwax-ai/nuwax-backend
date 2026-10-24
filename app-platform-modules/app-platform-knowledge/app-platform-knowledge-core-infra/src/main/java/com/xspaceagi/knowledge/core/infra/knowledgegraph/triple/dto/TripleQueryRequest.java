package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 三元组查询请求
 *
 * @author system
 * @date 2025-04-14
 */
@Data
@Schema(description = "三元组查询请求")
public class TripleQueryRequest {

    @Schema(description = "知识库ID")
    private Long knowledgeId;

    @Schema(description = "文档ID（MySQL中的文档主键）")
    private Long documentId;

    @Schema(description = "文档名称（模糊搜索）")
    private String documentName;

    @Schema(description = "主语（模糊搜索）")
    private String subject;

    @Schema(description = "谓语")
    private String predicate;

    @Schema(description = "宾语（模糊搜索）")
    private String object;
}
