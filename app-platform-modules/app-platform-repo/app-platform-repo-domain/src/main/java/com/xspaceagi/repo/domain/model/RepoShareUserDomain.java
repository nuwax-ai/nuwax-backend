package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档分享给个人（需求3：分享到空间之外的按人分享）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareUserDomain {

    private Long id;

    private Long tenantId;

    /**
     * 被分享的页面ID
     */
    private Long pageId;

    /**
     * 被分享人用户ID
     */
    private Long targetUserId;

    /**
     * 权限位组合（RepoConstants.PERM_*）
     */
    private Integer permBits;

    /**
     * 分享发起人ID
     */
    private Long createdBy;

    /**
     * 来源分享链接ID：链接访问自动加入「与我共享」时写入；个人分享覆盖为 NULL。
     */
    private Long linkId;

    /**
     * 软删除标记（仅 includeDeleted 查询返回时有意义）：1=有效 0=已删除
     */
    private Integer yn;

    private Date created;

    private Date modified;
}
