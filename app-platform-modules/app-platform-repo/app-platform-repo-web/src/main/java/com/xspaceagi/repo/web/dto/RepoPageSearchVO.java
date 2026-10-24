package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档页面搜索结果视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageSearchVO {

    /**
     * 相关度评分
     */
    private Double score;

    private Long pageId;

    private Long spaceId;

    private String slugId;

    private String title;

    /**
     * 正文摘要（纯文本，前端自行截断高亮）
     */
    private String snippet;

    private Long lastEditorId;

    /**
     * 最后编辑时间（epoch millis）
     */
    private Long editedAt;
}
