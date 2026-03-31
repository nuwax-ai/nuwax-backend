package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "微信 iLink 扫码开始响应")
public class ImWechatIlinkQrStartResponse {
    private String sessionId;
    @Schema(description = "二维码链接；终端无法展示时请用浏览器打开此链接扫码（对齐 openclaw-weixin 1.0.3）")
    private String qrcode;
    @Schema(description = "二维码图片内容 URL；可与 qrcode 二选一在浏览器中打开扫码")
    private String qrcodeImgContent;
}
