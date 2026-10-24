package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sidecar 鉴权回调响应体（裸 JSON，不套 ReqResult，Sidecar 按 response.ok + 字段解析）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCollabAuthResult {

    private Long userId;

    private Long tenantId;

    private Long spaceId;

    /**
     * 空间角色：Owner/Admin/User
     */
    private String role;

    /**
     * 是否可编辑（需求4/7 统一裁决结果；Sidecar 据此置连接只读）
     */
    private boolean editable;
}
