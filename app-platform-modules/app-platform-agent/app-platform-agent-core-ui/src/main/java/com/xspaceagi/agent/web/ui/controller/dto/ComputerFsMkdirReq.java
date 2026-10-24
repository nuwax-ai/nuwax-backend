package com.xspaceagi.agent.web.ui.controller.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文件系统新建目录请求")
public class ComputerFsMkdirReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "沙箱 id（仅用户个人沙箱支持选目录）")
    private Long sandboxId;

    @Schema(description = "父目录绝对路径（分隔符统一 /，来自 fsChildren 返回的 path）")
    private String parentPath;

    @Schema(description = "新目录名（支持中文等任意合法文件名，不含路径分隔符）")
    private String dirName;
}
