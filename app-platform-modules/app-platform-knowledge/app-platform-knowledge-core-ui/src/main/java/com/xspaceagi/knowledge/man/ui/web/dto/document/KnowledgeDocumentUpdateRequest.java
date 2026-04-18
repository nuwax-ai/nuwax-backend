package com.xspaceagi.knowledge.man.ui.web.dto.document;

import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.knowledge.sdk.vo.SegmentConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库文档-更改请求参数")
@Getter
@Setter
public class KnowledgeDocumentUpdateRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Document ID is required")
    private Long docId;

    @Schema(description = "文档名称")
    private String name;

    @Schema(description = "文档URL")
    private String docUrl;


    @Schema(description = "快速自动分段与清洗,true:无需分段设置,自动使用默认值")
    private Boolean autoSegmentConfigFlag;


    @Schema(description = "分段设置")
    private SegmentConfigModel segmentConfig;


    public static KnowledgeDocumentModel convert2Model(KnowledgeDocumentUpdateRequest updateDto) {
        KnowledgeDocumentModel knowledgeDocumentModel = new KnowledgeDocumentModel();
        knowledgeDocumentModel.setId(updateDto.getDocId());
        knowledgeDocumentModel.setKbId(null);
        knowledgeDocumentModel.setName(updateDto.getName());
        knowledgeDocumentModel.setDocUrl(updateDto.getDocUrl());
        knowledgeDocumentModel.setPubStatus(KnowledgePubStatusEnum.Waiting);
        knowledgeDocumentModel.setHasQa(Boolean.FALSE);
        knowledgeDocumentModel.setHasEmbedding(Boolean.FALSE);
        knowledgeDocumentModel.setSegmentConfig(updateDto.getSegmentConfig());
        knowledgeDocumentModel.setSpaceId(updateDto.getSpaceId());
        knowledgeDocumentModel.setCreated(null);
        knowledgeDocumentModel.setCreatorId(null);
        knowledgeDocumentModel.setCreatorName(null);
        knowledgeDocumentModel.setModified(null);
        knowledgeDocumentModel.setModifiedId(null);
        knowledgeDocumentModel.setModifiedName(null);
        return knowledgeDocumentModel;

    }

}
