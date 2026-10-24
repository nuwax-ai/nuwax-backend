package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 扫码授权发起结果。
 * <p>
 * <b>刻意不含授权 URL</b>：二维码由 {@code qrUrl} 指向的服务端端点渲染，待编码文本取自
 * state 行而非请求参数，授权 URL 因此全程不经客户端。这样既不给调用方留下
 * 「把 URL 打出来让用户自己复制」的余地（手机上复制粘贴一个 122 字符带 flow_id 的
 * URL 体验很差，而且容易被中间人替换），也不构成任意文本转二维码的 oracle。
 * <p>
 * {@code userCode} 仅供展示核对：用户在飞书确认页会看到同一个短码，能确认自己扫的是
 * 这一次会话。单独持有它无法完成授权——裸 verification_uri 里已含 flow_id，
 * 与 userCode 拼起来等价于完整授权 URL，故两者都不下发。
 */
@Schema(description = "扫码授权发起结果")
public record DeviceAuthorizeVo(
        @Schema(description = "本次走的分支：register=在 IdP 上注册新应用（产出应用身份），"
                + "authorize=对已有应用做用户授权（产出用户身份）。界面文案与下一步引导按它分流，"
                + "不要照 provider 配置自己猜——同一连接器会先后经历两种模式")
        String mode,
        @Schema(description = "轮询句柄，提交给 /device/poll 与 /device/qr")
        String state,
        @Schema(description = "飞书确认页上显示的短码，仅供用户核对")
        String userCode,
        @Schema(description = "授权窗口秒数，超时后需重新发起（实测飞书为 600）")
        int expiresIn,
        @Schema(description = "建议的首次轮询间隔秒数（实测飞书为 5）")
        int interval,
        @Schema(description = "二维码图片地址（PNG），直接作为 <img src> 使用")
        String qrUrl) {
}
