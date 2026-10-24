package com.xspaceagi.repo.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 按短链ID查询页面详情请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RepoPageGetBySlugRequest extends RepoPageRpcRequest {

    /**
     * 短链ID
     */
    private String slugId;
}
