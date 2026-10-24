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
 * 文档分享给个人实体（需求3）
 * @TableName repo_share_user
 */
@TableName(value = "repo_share_user")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoShareUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long pageId;

    private Long targetUserId;

    private Integer permBits;

    private Long createdBy;

    /**
     * 来源分享链接ID：链接访问自动加入「与我共享」时写入；个人分享覆盖为 NULL。删除链接时按此级联清理。
     */
    private Long linkId;

    private Date created;

    private Date modified;

    private Integer yn;
}
