package com.xspaceagi.repo.sdk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 页面搜索结果投影（SDK 对外，不直接复用 log-sdk 的 {@code SearchResult}，保持 sdk 仅依赖 spec）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageSearchRpcResult {

    private Long total;

    private List<RepoPageSearchRpcItem> items;

    /**
     * 单条搜索结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepoPageSearchRpcItem {

        private Double score;

        private Long spaceId;

        private Long pageId;

        private String slugId;

        private String title;

        private String content;

        private Long lastEditorId;

        private Long editedAt;
    }
}
