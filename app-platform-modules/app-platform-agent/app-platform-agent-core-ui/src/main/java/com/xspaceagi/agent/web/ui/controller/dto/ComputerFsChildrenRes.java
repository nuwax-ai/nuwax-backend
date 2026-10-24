package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件系统浏览单层子项（目录选择弹窗点父目录后的返回）：目录在前、名称自然排序。
 */
@Data
@Schema(description = "文件系统浏览单层子项")
public class ComputerFsChildrenRes implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前目录绝对路径（分隔符统一 /）")
    private String path;

    @Schema(description = "子项列表：目录在前、名称自然排序；文件由前端按 isDir 置灰")
    private List<ComputerFsEntry> entries;

    @SuppressWarnings("unchecked")
    public static ComputerFsChildrenRes from(Map<String, Object> body) {
        ComputerFsChildrenRes res = new ComputerFsChildrenRes();
        if (body == null) {
            return res;
        }
        Object path = body.get("path");
        res.setPath(path == null ? null : path.toString());
        Object entries = body.get("entries");
        if (entries instanceof List<?> list) {
            res.setEntries(list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> ComputerFsEntry.from((Map<String, Object>) item))
                    .toList());
        }
        return res;
    }
}
