package com.xspaceagi.im.wechat.ilink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 从 CDN 下载密文并解密（对齐 openclaw-weixin downloadAndDecryptBuffer）
 */
public final class WechatIlinkCdnDownloader {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private WechatIlinkCdnDownloader() {
    }

    public static byte[] downloadAndDecrypt(String encryptedQueryParam, String aesKeyBase64, String cdnBaseUrl) throws Exception {
        String url = WechatIlinkCdnUtil.buildCdnDownloadUrl(encryptedQueryParam, cdnBaseUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("CDN download HTTP " + resp.statusCode());
        }
        byte[] key = WechatIlinkAesEcb.parseAesKeyFromBase64(aesKeyBase64);
        return WechatIlinkAesEcb.decrypt(resp.body(), key);
    }
}
