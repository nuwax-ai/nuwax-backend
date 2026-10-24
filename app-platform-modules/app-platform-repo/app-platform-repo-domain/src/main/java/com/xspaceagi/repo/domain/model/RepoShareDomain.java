package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档分享到工作空间（需求4：权限分享而非源文件拷贝）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareDomain {

    private Long id;

    private Long tenantId;

    /**
     * 被分享的页面ID
     */
    private Long pageId;

    /**
     * 目标空间ID（该空间全部成员获得 perm_bits 权限）
     */
    private Long targetSpaceId;

    /**
     * 权限位组合（RepoConstants.PERM_*）
     */
    private Integer permBits;

    /**
     * 分享人ID
     */
    private Long createdBy;

    /**
     * 软删除标记（仅 includeDeleted 查询返回时有意义）：1=有效 0=已删除
     */
    private Integer yn;

    private Date created;

    private Date modified;
}
