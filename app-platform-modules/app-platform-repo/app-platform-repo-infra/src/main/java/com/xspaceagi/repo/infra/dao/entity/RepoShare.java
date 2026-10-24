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
 * 文档分享到工作空间实体（需求4）
 * @TableName repo_share
 */
@TableName(value = "repo_share")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoShare {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long pageId;

    private Long targetSpaceId;

    private Integer permBits;

    private Long createdBy;

    private Date created;

    private Date modified;

    private Integer yn;
}
