package com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识图谱列表查询请求
 *
 * @author system
 * @date 2026-04-16
 */
@Data
@Schema(description = "知识图谱列表查询请求")
public class KnowledgeGraphListRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long knowledgeId;

    @Schema(description = "文档名称（模糊搜索）")
    private String name;

    @Schema(description = "文件格式（如 pdf、docx、txt）")
    private String fileType;

    @Schema(description = "三元组解析状态：1-进行中，2-成功，10-失败")
    private Integer tripleStatus;

    @Schema(description = "创建时间排序：asc-升序，desc-降序")
    private String createdOrder;

    @Schema(description = "更新时间排序：asc-升序，desc-降序")
    private String updatedOrder;
}
