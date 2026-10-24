package com.xspaceagi.repo.sdk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 页面树节点投影（SDK 对外）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageTreeNodeRpcDto {

    /**
     * 页面信息
     */
    private RepoPageRpcDto page;

    /**
     * 子节点（按 position 排序）
     */
    @Builder.Default
    private List<RepoPageTreeNodeRpcDto> children = new ArrayList<>();
}
