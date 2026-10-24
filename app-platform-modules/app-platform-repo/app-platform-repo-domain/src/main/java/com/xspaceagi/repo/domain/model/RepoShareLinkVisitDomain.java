package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 分享链接访问日志领域对象（分享链接「统计」用，append-only）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareLinkVisitDomain {

    private Long id;

    private Long tenantId;

    private Long linkId;

    private Long pageId;

    private Long userId;

    private Date visitedAt;

    private Date created;
}
