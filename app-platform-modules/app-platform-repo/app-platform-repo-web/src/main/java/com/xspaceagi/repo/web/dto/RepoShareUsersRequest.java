package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 分享文档给个人请求（需求3）
 */
@Data
public class RepoShareUsersRequest {

    /**
     * 被分享人用户ID列表（多选）
     */
    private List<Long> targetUserIds;

    /**
     * 权限位组合：1查看|2编辑|4分享|8导出|16评论|32复制，查看恒含
     */
    private Integer permBits;
}
