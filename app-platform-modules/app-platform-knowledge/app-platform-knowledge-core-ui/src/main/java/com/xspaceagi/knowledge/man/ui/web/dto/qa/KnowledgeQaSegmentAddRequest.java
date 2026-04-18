package com.xspaceagi.knowledge.man.ui.web.dto.qa;

import com.xspaceagi.knowledge.core.spec.KnowledgeConstants;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库问答-新增请求参数")
@Getter
@Setter
public class KnowledgeQaSegmentAddRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;


    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base ID is required")
    private Long kbId;

    @Schema(description = "问题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Answer is required")
    private String question;

    @Schema(description = "答案", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Answer is required")
    private String answer;


    public static KnowledgeQaSegmentModel convert2Model(KnowledgeQaSegmentAddRequest addDto) {
        KnowledgeQaSegmentModel knowledgeQaSegmentModel = new KnowledgeQaSegmentModel();
        knowledgeQaSegmentModel.setId(null);
        //docId必填,这里手动添加的问答,默认0L
        knowledgeQaSegmentModel.setDocId(KnowledgeConstants.MANUAL_ADD_DOC_ID);
        knowledgeQaSegmentModel.setQuestion(addDto.getQuestion());
        knowledgeQaSegmentModel.setAnswer(addDto.getAnswer());
        knowledgeQaSegmentModel.setKbId(addDto.kbId);
        knowledgeQaSegmentModel.setHasEmbedding(null);
        knowledgeQaSegmentModel.setSpaceId(addDto.getSpaceId());
        knowledgeQaSegmentModel.setCreated(null);
        knowledgeQaSegmentModel.setCreatorId(null);
        knowledgeQaSegmentModel.setCreatorName(null);
        knowledgeQaSegmentModel.setModified(null);
        knowledgeQaSegmentModel.setModifiedId(null);
        knowledgeQaSegmentModel.setModifiedName(null);
        return knowledgeQaSegmentModel;

    }

}
