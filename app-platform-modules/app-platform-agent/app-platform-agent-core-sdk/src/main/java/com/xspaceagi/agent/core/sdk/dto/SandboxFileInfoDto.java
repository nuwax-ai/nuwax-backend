package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;

@Data
public class SandboxFileInfoDto {

    /**
     * 文件名称
     */
    private String name;

    /**
     * 是否为二进制文件
     */
    private boolean binary;

    /**
     * 文件大小是否超过限制
     */
    private boolean sizeExceeded;

    /**
     * 文件内容
     */
    private String contents;

    /**
     * 重命名前的文件名
     */
    private String renameFrom;

    /**
     * 操作类型：create | delete | rename | modify
     */
    private String operation;

    /**
     * 是否目录
     */
    private Boolean isDir = false;
}
