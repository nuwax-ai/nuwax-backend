package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 扫码结果轮询提交。
 * <p>
 * {@code interval} 由调用方回传上一轮拿到的值，服务端据此在收到 slow_down 时递增。
 * 服务端自己不记轮询次数、也不 sleep——把等待交给调用方，避免一个扫码会话
 * 长时间占住请求线程（授权窗口 600 秒、间隔 5 秒，最多约 120 次轮询）。
 */
@Data
@Schema(description = "扫码结果轮询提交")
public class DevicePollDto {

    @NotBlank(message = "必填")
    @Size(max = 512, message = "长度不能超过 512")
    @Schema(description = "发起扫码授权时拿到的 state")
    private String state;

    @Schema(description = "上一轮返回的建议间隔秒数；首次轮询可不传，服务端按协议默认值处理")
    private Integer interval;
}
