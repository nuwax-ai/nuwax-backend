package com.xspaceagi.repo.application.search;

import com.xspaceagi.log.sdk.annotation.SearchField;
import com.xspaceagi.log.sdk.annotation.SearchIndex;
import com.xspaceagi.log.sdk.vo.SearchDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 文档页面搜索文档（ES 索引 repo_page）。
 * <p>
 * id 使用页面ID字符串；正文为 html 快照提取的纯文本。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SearchIndex(indexName = "repo_page")
public class RepoPageSearchDocument extends SearchDocument {

    @Schema(description = "租户ID", hidden = true)
    private Long tenantId;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "页面ID")
    private Long pageId;

    @Schema(description = "短链ID")
    @SearchField(keyword = true, store = true)
    private String slugId;

    @Schema(description = "页面标题")
    @SearchField(store = true)
    private String title;

    @Schema(description = "正文纯文本（html 快照提取）")
    @SearchField(store = true)
    private String content;

    @Schema(description = "最后编辑人ID")
    private Long lastEditorId;

    @Schema(description = "最后编辑时间（epoch millis）")
    private Long editedAt;
}
