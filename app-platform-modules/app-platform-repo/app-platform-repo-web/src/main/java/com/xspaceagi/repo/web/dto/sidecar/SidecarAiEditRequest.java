package com.xspaceagi.repo.web.dto.sidecar;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 转发给 Sidecar /internal/ai-edit 的请求体（ai-edit.ts AiEditRequest）。
 * ops 为结构化编辑指令数组，元素形如 {"op":"appendBlocks","blocks":[...]}，透传不解析。
 */
@Data
public class SidecarAiEditRequest {

    /** `page:<slugId>`，与 WS documentName 一致 */
    private String documentName;

    /** 目标文档类型 doc/pptx/sheet；缺省按内容探测 */
    private String kind;

    /** 发起编辑的用户ID（写入 last_editor_id） */
    private Long editorId;

    private List<Map<String, Object>> ops;

    /** 页面 ID；AI 编辑 doc 后据此回写 html_snapshot/content_snapshot，让 docx/pdf/markdown 导出实时 */
    private Long pageId;

    /** 平台登录态 ticket；Sidecar 用它回调 /api/repo/pages/{pageId}/snapshot（Bearer 头） */
    private String ticket;
}
