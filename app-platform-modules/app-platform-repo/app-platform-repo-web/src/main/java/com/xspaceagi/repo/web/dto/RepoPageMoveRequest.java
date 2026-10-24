package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 移动文档页面请求。
 * beforeId/afterId 均为空时追加到目标父节点末尾。
 */
@Data
public class RepoPageMoveRequest {

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
