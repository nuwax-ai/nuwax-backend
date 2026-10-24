package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 扫码轮询结果。
 * <p>
 * 调用方只需 switch {@code status}，不必解析 {@code message}（后者是可直接展示给用户的中文）。
 * {@code pending} 时按 {@code interval} 秒后再来；其余五种都是终态，应停止轮询。
 */
@Schema(description = "扫码轮询结果")
public record DevicePollVo(
        @Schema(description = "pending 继续轮询 / authorized 已建连 / denied 用户拒绝 / "
                + "expired 授权窗口已过 / already_completed 其他页面已完成建连 / failed 上游确定性失败")
        String status,
        @Schema(description = "下一次轮询前应等待的秒数，仅 pending 时有值")
        Integer interval,
        @Schema(description = "新建的连接 id，仅 authorized 时有值")
        Long connectionId,
        @Schema(description = "连接器展示名")
        String displayName,
        @Schema(description = "可直接展示给用户的原因说明")
        String message) {
}
