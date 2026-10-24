package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档收藏领域对象（门户「我的收藏」）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageFavoriteDomain {

    private Long id;

    private Long tenantId;

    private Long userId;

    private Long pageId;

    private Date created;

    private Date modified;
}
