package com.xspaceagi.knowledge.sdk.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库表
 */
@Getter
@Setter
public class KnowledgeConfigVo {

    @Schema(description = "主键id")
    private Long id;

    @Schema(description = "知识库名称")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "发布状态")
    private String pubStatus;

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

    @Schema(description = "图标的url地址")
    private String icon;

    @Schema(description = "创建时间")
    private LocalDateTime created;

    @Schema(description = "创建人id")
    private Long creatorId;

    @Schema(description = "创建人")
    private String creatorName;

    @Schema(description = "创建人昵称")
    private String creatorNickName;

    @Schema(description = "头像")
    private String creatorAvatar;

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
}
