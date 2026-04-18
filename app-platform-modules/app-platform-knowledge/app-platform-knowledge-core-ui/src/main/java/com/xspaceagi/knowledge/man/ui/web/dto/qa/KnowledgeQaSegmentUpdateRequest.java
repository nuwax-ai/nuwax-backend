package com.xspaceagi.knowledge.man.ui.web.dto.qa;

import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库问答-新增请求参数")
@Getter
@Setter
public class KnowledgeQaSegmentUpdateRequest implements Serializable {


    @Schema(description = "问答ID")
    private Long id;

    @Schema(description = "问题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Answer is required")
    private String question;

    @Schema(description = "答案", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Answer is required")
    private String answer;


    public static KnowledgeQaSegmentModel convert2Model(KnowledgeQaSegmentUpdateRequest updateDto) {
        KnowledgeQaSegmentModel knowledgeQaSegmentModel = new KnowledgeQaSegmentModel();
        knowledgeQaSegmentModel.setId(updateDto.getId());
        knowledgeQaSegmentModel.setDocId(null);
        knowledgeQaSegmentModel.setQuestion(updateDto.getQuestion());
        knowledgeQaSegmentModel.setAnswer(updateDto.getAnswer());
        knowledgeQaSegmentModel.setKbId(null);
        knowledgeQaSegmentModel.setHasEmbedding(Boolean.FALSE);
        knowledgeQaSegmentModel.setSpaceId(null);
        knowledgeQaSegmentModel.setCreated(null);
        knowledgeQaSegmentModel.setCreatorId(null);
        knowledgeQaSegmentModel.setCreatorName(null);
        knowledgeQaSegmentModel.setModified(null);
        knowledgeQaSegmentModel.setModifiedId(null);
        knowledgeQaSegmentModel.setModifiedName(null);
        return knowledgeQaSegmentModel;

    }

}
