package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;

/**
 * 沙箱文件操作公共请求参数
 */
@Data
public class SandboxFileBaseRequest {

    /**
     * 会话归属用户ID
     */
    private Long conversationUserId;

    /**
     * 会话ID
     */
    private Long cId;

    /**
     * 自定义目标目录（可选）
     */
    private String customTargetDir;
}
