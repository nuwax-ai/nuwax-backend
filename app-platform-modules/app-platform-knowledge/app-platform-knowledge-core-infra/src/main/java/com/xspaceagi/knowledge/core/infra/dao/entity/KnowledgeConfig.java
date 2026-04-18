package com.xspaceagi.knowledge.core.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库表
 *
 * @TableName knowledge_config
 */
@TableName(value = "knowledge_config")
@Getter
@Setter
public class KnowledgeConfig {

    /**
     * 主键id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 发布状态
     */
    private KnowledgePubStatusEnum pubStatus;
    /**
     * 数据类型,默认文本,1:文本;2:表格
     */
    private Integer dataType;

    /**
     * 知识库的嵌入模型ID
     */
    private Long embeddingModelId;

    /**
     * 知识库的生成Q&A模型ID
     */
    private Long chatModelId;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;

    /**
     * 所属空间ID
     */
    private Long spaceId;

    /**
     * 图标的url地址
     */
    private String icon;

    /**
     * 文件大小,单位字节byte,用于预估对应知识库的参考大小值
     */
    private Long fileSize;

    /**
     * 创建时间
     */
    private LocalDateTime created;

    /**
     * 创建人id
     */
    private Long creatorId;

    /**
     * 创建人
     */
    private String creatorName;

    /**
     * 更新时间
     */
    private LocalDateTime modified;

    /**
     * 最后修改人id
     */
    private Long modifiedId;

    /**
     * 最后修改人
     */
    private String modifiedName;

    /**
     * 逻辑标记,1:有效;-1:无效
     */
    private Integer yn;

    /**
     * 工作流ID
     */
    private Long workflowId;

    /**
     * 是否受后台权限控制，0 不受，1 受
     */
    private Integer accessControl;

    /**
     * 全文检索同步状态: 0-未同步, 1-同步中, 2-已同步, -1-同步失败
     */
    private Integer fulltextSyncStatus;

    /**
     * 全文检索最后同步时间
     */
    private LocalDateTime fulltextSyncTime;

    /**
     * 已同步到全文检索的分段数量
     */
    private Long fulltextSegmentCount;
}
