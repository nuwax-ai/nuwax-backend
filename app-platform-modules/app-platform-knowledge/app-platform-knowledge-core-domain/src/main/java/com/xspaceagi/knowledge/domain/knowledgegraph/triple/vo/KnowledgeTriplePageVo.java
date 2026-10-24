package com.xspaceagi.knowledge.domain.knowledgegraph.triple.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库三元组分页查询响应
 *
 * @author system
 * @date 2026-04-22
 */
@Data
@Schema(description = "知识库三元组分页查询响应")
public class KnowledgeTriplePageVo implements Serializable {

    @Schema(description = "三元组列表")
    private List<TripleVo> list;

    @Schema(description = "总条数")
    private Long total;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页条数")
    private Integer pageSize;

    @Schema(description = "总页数")
    private Integer totalPages;
}
