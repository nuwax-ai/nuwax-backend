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
 * 文档分享链接实体（需求4）
 * @TableName repo_share_link
 */
@TableName(value = "repo_share_link")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoShareLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long pageId;

    private String token;

    private String accessType;

    /**
     * 是否允许评论（需求5）：1=允许 0=不允许
     */
    private Integer allowComment;

    /**
     * 链接授予权限位（本轮需求4：只读类，后端强制去编辑位；缺省仅查看）
     */
    private Integer permBits;

    private Long createdBy;

    private Date created;

    private Date modified;

    private Integer yn;
}
