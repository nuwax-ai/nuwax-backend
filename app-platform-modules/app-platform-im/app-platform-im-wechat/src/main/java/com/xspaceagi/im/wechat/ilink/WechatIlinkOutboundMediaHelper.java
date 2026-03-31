package com.xspaceagi.im.wechat.ilink;

/**
 * 出站富媒体：调用 {@link IlinkHttpClient#getUploadUrl} 后使用 {@link WechatIlinkAesEcb} 加密正文，
 * 再 PUT 到 {@link WechatIlinkCdnUtil#buildCdnUploadUrl}，最后组装带 {@link com.xspaceagi.im.wechat.ilink.dto.CDNMedia} 的 {@link com.xspaceagi.im.wechat.ilink.dto.MessageItem}。
 * 流程见 openclaw-weixin {@code send-media.ts} / {@code cdn-upload.ts}。
 */
public final class WechatIlinkOutboundMediaHelper {

    private WechatIlinkOutboundMediaHelper() {
    }
}
