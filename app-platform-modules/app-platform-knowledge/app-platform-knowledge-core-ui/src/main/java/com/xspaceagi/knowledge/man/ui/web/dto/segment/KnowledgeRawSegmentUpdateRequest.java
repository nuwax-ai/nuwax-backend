package com.xspaceagi.knowledge.man.ui.web.dto.segment;

import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库分段-更改请求参数")
@Getter
@Setter
public class KnowledgeRawSegmentUpdateRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;


    /**
     * 主键id
     */
    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long rawSegmentId;


    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Document ID is required")
    private Long docId;

    /**
     * 原始文本
     */
    @Schema(description = "原始文本")
    private String rawTxt;

    /**
     * 排序索引,在归属同一个文档下，段的排序
     */
    @Schema(description = "排序索引,在归属同一个文档下，段的排序")
    private Integer sortIndex;


    public static KnowledgeRawSegmentModel convert2Model(KnowledgeRawSegmentUpdateRequest updateDto) {
        KnowledgeRawSegmentModel knowledgeRawSegmentModel = new KnowledgeRawSegmentModel();
        knowledgeRawSegmentModel.setId(updateDto.getRawSegmentId());
        knowledgeRawSegmentModel.setDocId(updateDto.getDocId());
        knowledgeRawSegmentModel.setRawTxt(updateDto.getRawTxt());
        knowledgeRawSegmentModel.setKbId(null);
        knowledgeRawSegmentModel.setSortIndex(updateDto.getSortIndex());
        knowledgeRawSegmentModel.setSpaceId(updateDto.getSpaceId());
        knowledgeRawSegmentModel.setCreated(null);
        knowledgeRawSegmentModel.setCreatorId(null);
        knowledgeRawSegmentModel.setCreatorName(null);
        knowledgeRawSegmentModel.setModified(null);
        knowledgeRawSegmentModel.setModifiedId(null);
        knowledgeRawSegmentModel.setModifiedName(null);
        return knowledgeRawSegmentModel;

    }

}
