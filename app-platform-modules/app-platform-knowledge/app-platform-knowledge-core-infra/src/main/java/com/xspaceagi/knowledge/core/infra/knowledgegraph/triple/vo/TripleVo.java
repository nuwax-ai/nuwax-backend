package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 三元组返回对象
 *
 * @author system
 * @date 2025-04-14
 */
@Data
@Schema(description = "三元组返回对象")
public class TripleVo {

    @Schema(description = "三元组ID（SurrealDB中的ID）")
    private String id;

    @Schema(description = "主语")
    private String subject;

    @Schema(description = "谓语")
    private String predicate;

    @Schema(description = "宾语")
    private String object;

    @Schema(description = "知识库ID")
    private Long knowledgeId;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "分段ID")
    private Long segmentId;

    @Schema(description = "分段内容")
    private String segmentContent;
}
