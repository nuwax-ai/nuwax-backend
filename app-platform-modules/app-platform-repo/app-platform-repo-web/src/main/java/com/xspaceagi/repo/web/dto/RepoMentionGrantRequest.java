package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * @ 提及默认授权请求（选中联系人后由前端回传被提及的人）。
 */
@Data
public class RepoMentionGrantRequest {

    /** 被提及的用户ID列表（自己会被后端跳过） */
    private List<Long> userIds;
}