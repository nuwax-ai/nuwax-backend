package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档版本预览视图对象（HTML只读渲染）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVersionPreviewVO {

    private Long id;

    private Long pageId;

    private Long editorId;

    private String reason;

    private Date created;

    /**
     * 该版本渲染HTML快照
     */
    private String html;
}
