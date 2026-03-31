package com.xspaceagi.im.wechat.ilink;

import com.xspaceagi.im.wechat.ilink.dto.GetConfigResp;
import com.xspaceagi.im.wechat.ilink.dto.SendTypingBody;

import java.io.IOException;

/**
 * 可选协议能力：先 getConfig 取 typing_ticket 再 sendTyping（对齐 openclaw-weixin getConfig/sendTyping）。
 */
public final class WechatIlinkProtocolExtras {

    private static final int TYPING_STATUS_START = 1;
    private static final int TYPING_STATUS_CANCEL = 2;

    private WechatIlinkProtocolExtras() {
    }

    public static void sendTypingIndicator(IlinkHttpClient client,
                                           String baseUrl,
                                           String botToken,
                                           String ilinkUserId,
                                           String contextToken) throws IOException, InterruptedException {
        sendTypingIndicator(client, baseUrl, botToken, ilinkUserId, contextToken, TYPING_STATUS_START);
    }

    public static void cancelTypingIndicator(IlinkHttpClient client,
                                             String baseUrl,
                                             String botToken,
                                             String ilinkUserId,
                                             String contextToken) throws IOException, InterruptedException {
        sendTypingIndicator(client, baseUrl, botToken, ilinkUserId, contextToken, TYPING_STATUS_CANCEL);
    }

    private static void sendTypingIndicator(IlinkHttpClient client,
                                            String baseUrl,
                                            String botToken,
                                            String ilinkUserId,
                                            String contextToken,
                                            int status) throws IOException, InterruptedException {
        String apiBase = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : IlinkConstants.DEFAULT_BASE_URL;
        GetConfigResp cfg = client.getConfig(apiBase, botToken, ilinkUserId, contextToken, 10_000);
        if (cfg == null || (cfg.getRet() != null && cfg.getRet() != 0) || cfg.getTypingTicket() == null) {
            return;
        }
        SendTypingBody body = SendTypingBody.builder()
                .ilinkUserId(ilinkUserId)
                .typingTicket(cfg.getTypingTicket())
                .status(status)
                .build();
        client.sendTyping(apiBase, botToken, body, 10_000);
    }
}
