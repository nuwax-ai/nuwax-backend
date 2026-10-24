package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 沙箱文档 API：修改正文内容请求（透传 Sidecar ai-edit 的 kind + ops 指令数组）。
 * ops 元素形如 {"op":"appendBlocks","blocks":[...]}，详见 Sidecar 协议。
 */
@Data
public class SandboxContentEditRequest {

    /** 目标文档类型 doc/pptx/sheet；缺省按内容探测 */
    private String kind;

    /** 结构化编辑指令数组（按序执行） */
    private List<Map<String, Object>> ops;
}
