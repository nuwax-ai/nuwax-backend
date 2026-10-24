package com.xspaceagi.ctl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部接口「重启智能体电脑」的请求体。
 * <p>
 * tenantId 不在本体内：与其他内部接口一致走 query 参数显式声明，用于覆盖请求上下文
 * （见 InternalApiController#applyTenant）。
 * <p>
 * userId <b>必填且必须由调用方给准</b>：内部接口没有登录态，容器服务需要 userId 来定位
 * 「是谁的电脑」（选沙箱节点、拼 project_id/user_id、串幂等键都要用它），无法自行推导。
 * 接口只校验「该会话确实属于这个人、这个人确实属于声明的租户」，不判断「调用方是否有权
 * 代表这个人」——那是调用方业务侧的职责。
 */
@Data
@Schema(description = "重启智能体电脑请求")
public class InternalComputerRestartRequest {

    @Schema(description = "电脑（会话）归属用户ID（必填）。须属于调用方声明的租户，且必须是该电脑的归属人",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10086")
    private Long userId;

    @Schema(description = "会话ID（必填），即页面端 /api/computer/pod/restart 的 cId。须属于上面这个 userId",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "227861195531485140")
    private Long conversationId;

    @Schema(description = "容器环境：dev / prod（可选，不传由容器服务按默认处理）", example = "dev")
    private String appStage;
}
