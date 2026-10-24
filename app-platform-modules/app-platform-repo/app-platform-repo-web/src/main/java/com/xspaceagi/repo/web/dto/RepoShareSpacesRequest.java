package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 分享文档到工作空间请求（需求4）
 */
@Data
public class RepoShareSpacesRequest {

    /**
     * 目标空间ID列表（多选）
     */
    private List<Long> targetSpaceIds;

    /**
     * 权限位组合：1查看|2编辑|4分享|8导出，查看恒含
     */
    private Integer permBits;
}
