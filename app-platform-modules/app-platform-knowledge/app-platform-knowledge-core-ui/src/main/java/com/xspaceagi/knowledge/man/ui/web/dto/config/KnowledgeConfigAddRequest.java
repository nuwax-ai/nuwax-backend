package com.xspaceagi.knowledge.man.ui.web.dto.config;

import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库基础配置-新增请求参数")
@Getter
@Setter
public class KnowledgeConfigAddRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @NotNull(message = "Space ID is required")
    @Schema(
            description = "所属空间ID",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private Long spaceId;

    @Schema(
            description = "知识库名称",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "Knowledge base name is required")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    /**
     * 知识库的嵌入模型ID
     */
    @NotNull(message = "Embedding model is required")
    @Schema(
            description = "知识库的嵌入模型ID",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private Long embeddingModelId;

    /**
     * 数据类型,默认文本,1:文本;2:表格
     */
    @Schema(
            description = "数据类型,默认文本,1:文本;2:表格",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private Integer dataType;

    @Schema(description = "图标的url地址")
    private String icon;

    @Schema(description = "工作流ID")
    private Long workflowId;

    public static KnowledgeConfigModel convert2Model(
            KnowledgeConfigAddRequest addDto
    ) {
        KnowledgeConfigModel knowledgeConfigModel = new KnowledgeConfigModel();
        knowledgeConfigModel.setId(null);
        knowledgeConfigModel.setName(addDto.getName());
        knowledgeConfigModel.setDescription(addDto.getDescription());
        knowledgeConfigModel.setPubStatus(KnowledgePubStatusEnum.Waiting);
        knowledgeConfigModel.setDataType(addDto.getDataType());
        knowledgeConfigModel.setEmbeddingModelId(addDto.getEmbeddingModelId());
        knowledgeConfigModel.setChatModelId(null);
        knowledgeConfigModel.setSpaceId(addDto.getSpaceId());
        knowledgeConfigModel.setIcon(addDto.getIcon());
        knowledgeConfigModel.setCreated(null);
        knowledgeConfigModel.setCreatorId(null);
        knowledgeConfigModel.setCreatorName(null);
        knowledgeConfigModel.setModified(null);
        knowledgeConfigModel.setModifiedId(null);
        knowledgeConfigModel.setModifiedName(null);
        knowledgeConfigModel.setWorkflowId(addDto.getWorkflowId());
        return knowledgeConfigModel;
    }
}
