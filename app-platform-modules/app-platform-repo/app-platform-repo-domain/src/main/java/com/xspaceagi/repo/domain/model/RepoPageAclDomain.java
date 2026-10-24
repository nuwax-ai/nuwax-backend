package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档级按人权限条目（需求7：开启按人权限后，未配置成员降为仅阅读）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageAclDomain {

    private Long id;

    private Long tenantId;

    private Long pageId;

    private Long userId;

    /**
     * 权限位组合（RepoConstants.PERM_*）
     */
    private Integer permBits;

    private Date created;

    private Date modified;
}
