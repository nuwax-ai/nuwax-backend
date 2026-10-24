package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 分享链接访问统计中的单个去重访问用户（最近访问时间）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareLinkVisitUserVO {

    private Long userId;

    /**
     * 展示名（昵称优先，空则用户名）
     */
    private String userName;

    private String avatar;

    /**
     * 该用户最近一次访问该链接的时间
     */
    private Date lastVisitedAt;
}
