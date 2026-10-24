package com.xspaceagi.knowledge.core.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.knowledge.sdk.enums.KnowledgeDocStatueEnum;
import com.xspaceagi.knowledge.sdk.enums.KnowledgePubStatusEnum;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库-原始文档表
 *
 * @TableName knowledge_document
 */
@TableName(value = "knowledge_document")
@Getter
@Setter
public class KnowledgeDocument {
    /**
     * 主键id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文档所属知识库
     */
    private Long kbId;

    /**
     * 文档名称
     */
    private String name;

    /**
     * 文件URL
     */
    private String docUrl;

    /**
     * 发布状态
     */
    private KnowledgePubStatusEnum pubStatus;

    /**
     * 文档状态：1-分析中，2-分析成功，10-分析失败
     * 注意：此字段不在数据库中，由 KnowledgeDocumentRepository.updateDocumentsStatus() 动态计算
     */
    @TableField(exist = false)
    private KnowledgeDocStatueEnum docStatus;

    /**
     * 是否已经生成Q&A
     */
    private Boolean hasQa;

    /**
     * 是否已经完成嵌入
     */
    private Boolean hasEmbedding;

    /**
     * 文档分段方式（需要记录分段方式，基于字符数量或换行，Q&A字段等）。如果为空，表示还没有进行分段
     */
    private String segment;

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
     * 自定义文本内容,自定义添加会有
     */
    private String fileContent;

    /**
     * 文件大小,单位字节byte
     */
    private Long fileSize;

    /**
     * 文件类型,1:URL访问文件;2:自定义文本内容
     */
    private Integer dataType;

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
}