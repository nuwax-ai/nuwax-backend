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
 * 文档级按人权限实体（需求7）
 * @TableName repo_page_acl
 */
@TableName(value = "repo_page_acl")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPageAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long pageId;

    private Long userId;

    private Integer permBits;

    private Date created;

    private Date modified;

    private Integer yn;
}
