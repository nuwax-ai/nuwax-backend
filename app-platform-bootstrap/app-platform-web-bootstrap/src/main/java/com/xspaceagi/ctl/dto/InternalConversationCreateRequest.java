package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「创建智能体会话」的请求体。
 * <p>
 * tenantId 不在本体内：与其他内部接口一致走 query 参数显式声明，用于覆盖请求上下文
 * （见 InternalApiController#applyTenant）。userId 是会话的归属用户，接口会在
 * 「以该用户身份」的上下文下完成权限校验后建会话（调用方无需登录态）。
 */
@Data
public class InternalConversationCreateRequest implements Serializable {

    @Schema(description = "会话归属用户ID（必填）。须属于调用方声明的租户")
    private Long userId;

    @Schema(description = "智能体ID（必填）")
    private Long agentId;

    @Schema(description = "是否开发（调试）模式：true 走智能体空间权限校验，false 走已发布智能体执行权限校验；默认 false")
    private boolean devMode;

    @Schema(description = "用户填写的会话变量内容（可选）")
    private Map<String, Object> variables;

    @Schema(description = "沙箱ID（可选）。-1 为云端沙箱哨兵值，会分配真实云端沙箱；传 workspacePath 时必传")
    private Long sandboxId;

    @Schema(description = "会话工作目录（可选），仅当 sandboxId 为用户个人电脑（USER 类型沙箱）时生效")
    private String workspacePath;

    @Schema(description = "项目ID（统一项目登记 id，可选）。传后会话关联该项目并置为开发会话")
    private Long projectId;

    @Schema(description = "项目类型 NormalProject / UserApp（可选），projectId 非空时必传")
    private String projectType;
}
