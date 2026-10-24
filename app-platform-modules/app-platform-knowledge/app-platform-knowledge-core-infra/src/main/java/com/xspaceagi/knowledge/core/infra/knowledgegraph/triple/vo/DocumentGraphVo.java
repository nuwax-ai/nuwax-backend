package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 文档知识图谱返回对象
 *
 * @author system
 * @date 2025-04-14
 */
@Data
@Schema(description = "文档知识图谱返回对象")
public class DocumentGraphVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "知识库ID")
    private Long knowledgeId;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "三元组列表")
    private List<TripleVo> triples;

    @Schema(description = "三元组数量")
    private Integer tripleCount;

    @Schema(description = "主语节点数量")
    private Integer subjectCount;

    @Schema(description = "宾语节点数量")
    private Integer objectCount;
}
