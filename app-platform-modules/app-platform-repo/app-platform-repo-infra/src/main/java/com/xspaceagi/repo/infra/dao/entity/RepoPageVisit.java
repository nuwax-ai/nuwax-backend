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
 * 文档访问记录实体（需求1：门户最近访问）
 * @TableName repo_page_visit
 */
@TableName(value = "repo_page_visit")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPageVisit {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long userId;

    private Long pageId;

    /**
     * 来源分享链接ID：通过链接访问时写入；正常访问覆盖为 NULL。删除链接时按此级联清理。
     */
    private Long linkId;

    private Date lastVisitedAt;

    private Date created;

    private Date modified;

    private Integer yn;
}
