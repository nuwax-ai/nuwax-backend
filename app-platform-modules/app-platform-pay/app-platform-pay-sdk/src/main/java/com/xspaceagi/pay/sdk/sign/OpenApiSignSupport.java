package com.xspaceagi.pay.sdk.sign;

/** 各 {@code OpenApi*Sign} 共用的签名流程。 */
final class OpenApiSignSupport {

    private OpenApiSignSupport() {}

    static byte[] sign(String path, Object request, String clientSecret) {
        long timestamp = OpenApiClientSignHelper.nowTimestamp();
        String nonce = OpenApiClientSignHelper.newNonce();
        OpenApiSignTimestamps.apply(request, timestamp, nonce);
        return OpenApiClientSignHelper.buildSignedPostBody(path, request, timestamp, nonce, clientSecret);
    }
}
