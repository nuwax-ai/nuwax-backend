package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文件系统目录重命名请求")
public class ComputerFsRenameReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "沙箱 id（仅用户个人沙箱支持选目录）")
    private Long sandboxId;

    @Schema(description = "现目录绝对路径（分隔符统一 /，来自 fsChildren 返回的 path）")
    private String path;

    @Schema(description = "新名字（仅名字、不含路径分隔符，不支持跨目录移动；支持中文）")
    private String newName;
}
