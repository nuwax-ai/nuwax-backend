package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分享链接访问统计（访问次数 + 去重访问用户列表）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareLinkStatsVO {

    /**
     * 访问次数（PV，登录态每次通过链接访问计一次）
     */
    private long visitCount;

    /**
     * 去重访问用户列表（按最近访问时间倒序）
     */
    private List<RepoShareLinkVisitUserVO> users;
}
