package com.xspaceagi.userapp.domain.gateway;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 下游容器服务的连接信息
 */
@Data
@AllArgsConstructor
public class UserAppSandboxHandle {

    /** 沙箱ID（落库到任务行，轮询/取消时回到同一台） */
    private String sandboxId;

    /** 服务基础地址（agent 管理 API：dev/start、build、tasks 等） */
    private String baseUrl;

    /** x-api-key 认证密钥 */
    private String apiKey;

    /** 用户侧流量入口 */
    private String vncBaseUrl;
}
