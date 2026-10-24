package com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档三元组解析状态表
 *
 * @TableName document_parse_status
 */
@TableName(value = "document_parse_status")
@Data
public class DocumentParseStatus {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文档ID（逻辑外键）
     */
    private Long documentId;

    /**
     * 知识库ID
     */
    private Long kbId;

    /**
     * 三元组解析状态：0-未开始，1-进行中，2-成功，3-禁用，10-失败
     */
    @TableField("triple_status")
    private Integer tripleStatus;

    /**
     * 任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 租户ID
     */
    @TableField("_tenant_id")
    private Long tenantId;

    /**
     * 创建时间
     */
    private LocalDateTime created;

    /**
     * 更新时间
     */
    private LocalDateTime modified;
}
