package com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱列表项返回对象
 *
 * @author system
 * @date 2026-04-16
 */
@Data
@Schema(description = "知识图谱列表项")
public class KnowledgeGraphListItemVo {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "知识图谱状态：0-未开始，1-进行中，2-成功，3-禁用，10-失败")
    private Integer tripleStatus;

    @Schema(description = "文件格式")
    private String fileType;

    @Schema(description = "上传时间")
    private LocalDateTime createdTime;

    @Schema(description = "更新时间")
    private LocalDateTime updatedTime;
}
