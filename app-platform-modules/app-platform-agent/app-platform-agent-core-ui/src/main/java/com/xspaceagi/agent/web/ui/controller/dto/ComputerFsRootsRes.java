package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件系统浏览根列表（目录选择弹窗起点）：Windows 为盘符列表、mac/Linux 为 /
 */
@Data
@Schema(description = "文件系统浏览根列表")
public class ComputerFsRootsRes implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "根目录列表（Windows 盘符；mac/Linux 为 /）")
    private List<ComputerFsEntry> roots;

    @Schema(description = "用户 home 目录（前端作为快捷入口）")
    private String home;

    @SuppressWarnings("unchecked")
    public static ComputerFsRootsRes from(Map<String, Object> body) {
        ComputerFsRootsRes res = new ComputerFsRootsRes();
        if (body == null) {
            return res;
        }
        Object roots = body.get("roots");
        if (roots instanceof List<?> list) {
            res.setRoots(list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> ComputerFsEntry.from((Map<String, Object>) item))
                    .toList());
        }
        Object home = body.get("home");
        res.setHome(home == null ? null : home.toString());
        return res;
    }
}
