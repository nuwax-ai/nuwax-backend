package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件系统浏览条目（目录选择弹窗用）
 */
@Data
@Schema(description = "文件系统浏览条目")
public class ComputerFsEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "绝对路径（分隔符统一 /）")
    private String path;

    @Schema(description = "是否目录；文件由前端置灰不可选")
    private Boolean isDir;

    @Schema(description = "是否符号链接（指向目录时可进入，前端可标注）")
    private Boolean isSymlink;

    public static ComputerFsEntry from(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        ComputerFsEntry entry = new ComputerFsEntry();
        entry.setName((String) map.get("name"));
        entry.setPath((String) map.get("path"));
        entry.setIsDir(Boolean.TRUE.equals(map.get("isDir")));
        entry.setIsSymlink(Boolean.TRUE.equals(map.get("isSymlink")));
        return entry;
    }
}
