package com.xspaceagi.agent.web.ui.controller.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "查询文件列表响应")
public class ComputerFileListRes {

    @Schema(description = "文件列表")
    private Object files;

    @Schema(description = """
            file-server 实际采用的递归模式：true=全量扁平，false=单层。
            旧版 file-server 不返回该字段时为 null，前端可据此做兼容。
            """)
    private Boolean recursive;

    /**
     * 从 file-server 响应组装（files 已由应用层按 type/limit 归一，新旧 FS 行为一致）
     */
    public static ComputerFileListRes from(Map<String, Object> result) {
        ComputerFileListRes res = new ComputerFileListRes();
        res.setFiles(result.get("files"));
        // 仅透传 file-server 回显的 recursive；旧 FS 无该字段则保持 null，供前端判断能力
        Object recursiveObj = result.get("recursive");
        res.setRecursive(recursiveObj instanceof Boolean ? (Boolean) recursiveObj : null);
        return res;
    }
}
