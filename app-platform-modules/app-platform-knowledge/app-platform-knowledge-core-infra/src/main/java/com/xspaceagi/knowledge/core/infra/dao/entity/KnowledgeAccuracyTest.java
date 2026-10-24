package com.xspaceagi.knowledge.core.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库准确性测试历史表
 *
 * @TableName knowledge_recall_verification
 */
@TableName(value = "knowledge_recall_verification")
@Getter
@Setter
public class KnowledgeAccuracyTest {

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
     * 查询内容
     */
    private String queryText;

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
}
