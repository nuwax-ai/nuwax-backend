package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 页面树节点（空间内全量树在应用服务内一次性组装）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageTreeNode {

    private RepoPageDomain page;

    @Builder.Default
    private List<RepoPageTreeNode> children = new ArrayList<>();
}
