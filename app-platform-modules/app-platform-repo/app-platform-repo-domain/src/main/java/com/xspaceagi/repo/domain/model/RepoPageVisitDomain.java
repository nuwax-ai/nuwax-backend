package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档访问记录领域对象（需求1：门户最近访问）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVisitDomain {

    private Long id;

    private Long tenantId;

    private Long userId;

    private Long pageId;

    /**
     * 来源分享链接ID：通过链接访问时写入；正常访问覆盖为 NULL。
     */
    private Long linkId;

    private Date lastVisitedAt;

    private Date created;

    private Date modified;
}
