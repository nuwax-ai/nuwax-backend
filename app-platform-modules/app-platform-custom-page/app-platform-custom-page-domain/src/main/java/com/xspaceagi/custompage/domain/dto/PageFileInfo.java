package com.xspaceagi.custompage.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class PageFileInfo {

    @Schema(description = "File name")
    private String name;

    @Schema(description = "Whether file is binary")
    private boolean binary;

    @Schema(description = "Whether file size exceeds limit")
    private boolean sizeExceeded;

    @Schema(description = "File text content")
    private String contents;

    @Schema(description = "Previous name before rename")
    private String renameFrom;

    //create | delete | rename | modify
    @Schema(description = "Operation type")
    private String operation;

    @Schema(description = "Whether entry is a directory")
    private Boolean isDir = false;
}