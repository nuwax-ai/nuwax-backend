package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档页面树节点视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageTreeNodeVO {

    /**
     * 页面信息
     */
    private RepoPageVO page;

    /**
     * 子节点（按 position 排序）
     */
    @Builder.Default
    private List<RepoPageTreeNodeVO> children = new ArrayList<>();
}
