package com.xspaceagi.repo.web.dto.sidecar;

import lombok.Data;

/**
 * Sidecar /internal/tree-changed 返回（ai-edit.ts TreeChangedResult）。
 * 树结构变更广播是尽力而为信号，调用方只关心副作用（广播进 Yjs meta.treeVersion），
 * 无需解析具体值，仅据此判断 Success。
 */
@Data
public class SidecarTreeChangedResult {

    private boolean ok;

    /** 实际写入 meta.treeVersion 的内存 Y.Doc 数量（≈ 有在线连接的文档数） */
    private Integer broadcast;

    /** 失败时的错误信息（ok=false） */
    private String error;
}
