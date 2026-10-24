package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档级按人权限状况（需求7）：开关 + 条目列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoAclStatus {

    /**
     * 按人权限开关：0=关闭（成员默认可读写） 1=开启（未配置成员降为仅阅读）
     */
    private Integer aclEnabled;

    /**
     * 按人权限条目
     */
    private List<RepoPageAclDomain> entries;
}
