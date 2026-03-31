package com.xspaceagi.knowledge.man.ui.web.dto.config;

import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.Objects;

@Schema(description = "知识库基础配置-修改请求参数")
@Getter
@Setter
public class KnowledgeConfigUpdateRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(
            description = "所属空间ID",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private Long spaceId;

    /**
     * 主键id
     */
    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(
            description = "知识库名称",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "知识库名称不能为空")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

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

    @Schema(description = "模型编号")
    private Long embeddingModelId;

    public static KnowledgeConfigModel convert2Model(
            KnowledgeConfigUpdateRequest updateDto
    ) {
        KnowledgeConfigModel knowledgeConfigModel = new KnowledgeConfigModel();
        knowledgeConfigModel.setId(updateDto.getId());
        if (StringUtils.isNoneBlank(updateDto.getName())) {
            knowledgeConfigModel.setName(updateDto.getName());
        }
        if (StringUtils.isNotBlank(updateDto.getDescription())) {
            knowledgeConfigModel.setDescription(updateDto.getDescription());
        }
        //修改模式,置为空
        knowledgeConfigModel.setPubStatus(null);
        if (Objects.nonNull(updateDto.getDataType())) {
            knowledgeConfigModel.setDataType(updateDto.getDataType());
        }
        //knowledgeConfigModel.setEmbeddingModelId(null);
        knowledgeConfigModel.setEmbeddingModelId(updateDto.getEmbeddingModelId());
        knowledgeConfigModel.setChatModelId(null);

        if (Objects.nonNull(updateDto.getSpaceId())) {
            knowledgeConfigModel.setSpaceId(updateDto.getSpaceId());
        }
        if (Objects.nonNull(updateDto.getIcon())) {
            knowledgeConfigModel.setIcon(updateDto.getIcon());
        }
        knowledgeConfigModel.setCreated(null);
        knowledgeConfigModel.setCreatorId(null);
        knowledgeConfigModel.setCreatorName(null);
        knowledgeConfigModel.setModified(null);
        knowledgeConfigModel.setModifiedId(null);
        knowledgeConfigModel.setModifiedName(null);
        knowledgeConfigModel.setWorkflowId(updateDto.getWorkflowId());
        return knowledgeConfigModel;
    }
}
