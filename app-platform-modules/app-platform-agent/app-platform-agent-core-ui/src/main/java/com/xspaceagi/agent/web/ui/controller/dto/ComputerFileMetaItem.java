package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件元数据条目（批量查询，与请求 filePaths 顺序一致）
 */
@Data
@Schema(description = "文件元数据条目")
public class ComputerFileMetaItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "回显请求路径（trimmed），响应与请求条目可按键/按序关联；规范路径以 file-list 返回的 name 为准")
    private String path;

    @Schema(description = "是否目录；查询失败条目为 null")
    private Boolean isDir;

    @Schema(description = "是否符号链接（lstat 不跟随链接，size 为链接条目自身而非目标文件）")
    private Boolean isLink;

    @Schema(description = "文件大小（字节）；目录恒为 null")
    private Long size;

    @Schema(description = "最后修改时间（epoch 毫秒）")
    private Long mtimeMs;

    @Schema(description = "小写扩展名，不含点（如 md/png）；文件无扩展名为空串，目录为 null")
    private String extension;

    @Schema(description = "按扩展名查表所得 MIME 类型（如 image/png）；未知类型回落 application/octet-stream；目录为 null")
    private String mimeType;

    @Schema(description = "符号链接的目标路径（readlink 原样返回，可相对可绝对）；仅软链条目非 null")
    private String linkTarget;

    @Schema(description = "目录子项数量（口径同文件列表；达 1000 截断，值语义为 ≥1000）；仅目录条目非 null")
    private Long childCount;

    @Schema(description = "该条查询失败原因（如 ENOENT=不存在、illegal path=非法路径）；成功条目为 null")
    private String error;

    public static ComputerFileMetaItem from(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        ComputerFileMetaItem item = new ComputerFileMetaItem();
        item.setPath(asString(map.get("path")));
        item.setIsDir(asBoolean(map.get("isDir")));
        item.setIsLink(asBoolean(map.get("isLink")));
        item.setSize(asLong(map.get("size")));
        item.setMtimeMs(asLong(map.get("mtimeMs")));
        item.setExtension(asString(map.get("extension")));
        item.setMimeType(asString(map.get("mimeType")));
        item.setLinkTarget(asString(map.get("linkTarget")));
        item.setChildCount(asLong(map.get("childCount")));
        item.setError(asString(map.get("error")));
        return item;
    }

    /** 三态布尔：字段缺失保持 null，存在按 Boolean.TRUE.equals 归一 */
    private static Boolean asBoolean(Object value) {
        return value == null ? null : Boolean.TRUE.equals(value);
    }

    /** 数值容错转换（Jackson 反序列化 Integer/Long/Double 均归一为 Long） */
    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
