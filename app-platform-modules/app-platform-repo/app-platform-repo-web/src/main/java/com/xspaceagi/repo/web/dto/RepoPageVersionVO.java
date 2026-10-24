package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档版本视图对象（列表用，不含大字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVersionVO {

    private Long id;

    private Long pageId;

    private Long editorId;

    /**
     * autosave/manual/restore
     */
    private String reason;

    private Date created;
}
