package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxFileListRequest extends SandboxFileBaseRequest {

    /**
     * 代理路径前缀
     */
    private String proxyPath;

    /**
     * 相对工作区根的目录路径；recursive=false 时用于单层浏览
     */
    private String relativePath;

    /**
     * 是否递归扁平列出；null/true=全量，false=单层
     */
    private Boolean recursive;

    /**
     * 返回条目类型：file-仅文件、dir-仅目录（兼容 directory）、null-全部；
     */
    private String type;

    /**
     * 最多返回条目数（非负，类型过滤后计数）；null 不限，负数按不限处理
     */
    private Integer limit;
}
