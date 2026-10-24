package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 移动页面请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageMoveRequest extends RepoPageRpcRequest {

    /**
     * 被移动页面ID
     */
    private Long pageId;

    /**
     * 目标父页面ID，null=根节点
     */
    private Long targetParentId;

    /**
     * 目标位置前一个兄弟页面ID，可空
     */
    private Long beforeId;

    /**
     * 目标位置后一个兄弟页面ID，可空
     */
    private Long afterId;
}
