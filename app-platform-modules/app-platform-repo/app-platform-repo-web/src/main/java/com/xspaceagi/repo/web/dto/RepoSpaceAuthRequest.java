package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * Sidecar 空间信号文档鉴权回调请求体（信号 doc 方案）。
 * <p>
 * 信号文档 `tree_&lt;spaceId&gt;` 仅承载 meta.treeVersion 唤醒信号（无文档内容），前端
 * 任意页面连它接收「该空间目录树服务器端变更」。鉴权回调不带页面 slugId，只带 spaceId 做空间成员校验。
 */
@Data
public class RepoSpaceAuthRequest {

    /**
     * 前端携带的平台 JWT
     */
    private String token;

    /**
     * 空间 id（信号文档名约定 tree_&lt;spaceId&gt;）
     */
    private Long spaceId;
}
