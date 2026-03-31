package com.xspaceagi.im.wechat.ilink;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * CDN URL 构造（对齐 openclaw-weixin src/cdn/cdn-url.ts）
 */
public final class WechatIlinkCdnUtil {

    private WechatIlinkCdnUtil() {
    }

    public static String buildCdnDownloadUrl(String encryptedQueryParam, String cdnBaseUrl) {
        String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
        return base + "/download?encrypted_query_param=" + URLEncoder.encode(encryptedQueryParam, StandardCharsets.UTF_8);
    }

    public static String buildCdnUploadUrl(String cdnBaseUrl, String uploadParam, String filekey) {
        String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
        return base + "/upload?encrypted_query_param=" + URLEncoder.encode(uploadParam, StandardCharsets.UTF_8)
                + "&filekey=" + URLEncoder.encode(filekey, StandardCharsets.UTF_8);
    }
}
