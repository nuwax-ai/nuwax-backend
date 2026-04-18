package com.xspaceagi.knowledge.domain.model;

import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 知识库表
 *
 * @TableName knowledge_config
 */
@Data
public class KnowledgeConfigModel {

    @Schema(description = "主键id")
    private Long id;

    @Schema(description = "知识库名称")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "发布状态")
    private KnowledgePubStatusEnum pubStatus;

    /**
     * 数据类型,默认文本,1:文本;2:表格
     */
    @Schema(description = "数据类型,默认文本,1:文本;2:表格")
    private Integer dataType;

    @Schema(description = "知识库的嵌入模型ID")
    private Long embeddingModelId;

    @Schema(description = "知识库的生成Q&A模型ID")
    private Long chatModelId;

    @Schema(description = "所属空间ID")
    private Long spaceId;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "图标的url地址")
    private String icon;

    @Schema(
            description = "文件大小,单位字节byte,用于预估对应知识库的参考大小值"
    )
    private Long fileSize;

    @Schema(description = "创建时间")
    private LocalDateTime created;

    @Schema(description = "创建人id")
    private Long creatorId;

    @Schema(description = "创建人")
    private String creatorName;

    @Schema(description = "更新时间")
    private LocalDateTime modified;

    @Schema(description = "最后修改人id")
    private Long modifiedId;

    @Schema(description = "最后修改人")
    private String modifiedName;

    @Schema(description = "工作流ID")
    private Long workflowId;

    @Schema(description = "是否受后台权限控制，0 不受，1 受")
    private Integer accessControl;

    @Schema(description = "全文检索同步状态: 0-未同步, 1-同步中, 2-已同步, -1-同步失败")
    private Integer fulltextSyncStatus;

    @Schema(description = "全文检索最后同步时间")
    private LocalDateTime fulltextSyncTime;

    @Schema(description = "已同步到全文检索的分段数量")
    private Long fulltextSegmentCount;

    public static KnowledgeConfigVo convertFromModel(
            KnowledgeConfigModel model
    ) {
        if (Objects.isNull(model)) {
            return null;
        }
        KnowledgeConfigVo knowledgeConfigVo = new KnowledgeConfigVo();
        knowledgeConfigVo.setId(model.getId());
        knowledgeConfigVo.setName(model.getName());
        knowledgeConfigVo.setDescription(model.getDescription());
        knowledgeConfigVo.setPubStatus(String.valueOf(model.getPubStatus()));
        knowledgeConfigVo.setDataType(model.getDataType());
        knowledgeConfigVo.setEmbeddingModelId(model.getEmbeddingModelId());
        knowledgeConfigVo.setChatModelId(model.getChatModelId());
        knowledgeConfigVo.setSpaceId(model.getSpaceId());
        knowledgeConfigVo.setIcon(model.getIcon());
        knowledgeConfigVo.setCreated(model.getCreated());
        knowledgeConfigVo.setCreatorId(model.getCreatorId());
        knowledgeConfigVo.setCreatorName(model.getCreatorName());
        knowledgeConfigVo.setModified(model.getModified());
        knowledgeConfigVo.setModifiedId(model.getModifiedId());
        knowledgeConfigVo.setModifiedName(model.getModifiedName());
        knowledgeConfigVo.setWorkflowId(model.getWorkflowId());
        knowledgeConfigVo.setAccessControl(model.getAccessControl());
        return knowledgeConfigVo;
    }
}
