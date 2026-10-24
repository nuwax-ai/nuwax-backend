package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 复制页面请求。
 * <p>
 * targetSpaceId 与 targetParentId 均为 null 时 = 同级末尾复制（标题=原标题+(副本)，内容三件套原样拷贝）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageCopyRequest extends RepoPageRpcRequest {

    /**
     * 源页面ID
     */
    private Long pageId;

    /**
     * 目标空间ID；null=沿用源空间（同级末尾），等于源空间也可
     */
    private Long targetSpaceId;

    /**
     * 目标父页面ID（文件夹）；null=目标空间根级
     */
    private Long targetParentId;
}
