package com.xspaceagi.repo.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 文档 AI 编辑会话映射实体（用户 × 文档 → AI 编辑会话）
 *
 * @TableName repo_doc_edit_conversation
 */
@TableName(value = "repo_doc_edit_conversation")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoDocEditConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long userId;

    private Long pageId;

    private String slugId;

    private Long editConversationId;

    private Date created;

    private Date modified;

    private Integer yn;
}
