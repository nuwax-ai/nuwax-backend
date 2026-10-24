package com.xspaceagi.repo.web.dto.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Sidecar /internal/ai-edit 返回（ai-edit.ts AiEditResult）。
 * read 时按 kind 回填 doc / slides / snapshot 三选一。
 */
@Data
public class SidecarAiEditResult {

    private boolean ok;

    private String kind;

    private Integer applied;

    private Integer skipped;

    /** kind=doc 时的 ProseMirror 文档 JSON */
    private JsonNode doc;

    /** kind=pptx 时的幻灯片模型 JSON 数组 */
    private JsonNode slides;

    /** kind=sheet 时的表格快照（未落盘为 null） */
    private JsonNode snapshot;

    /** 失败时的错误信息（ok=false） */
    private String error;
}
