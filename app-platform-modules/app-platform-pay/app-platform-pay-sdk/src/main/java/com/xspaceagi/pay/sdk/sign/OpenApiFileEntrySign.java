package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.FileEntryOpenApiUploadRequest;

/** 文件 Open API 路径与签名辅助 */
public final class OpenApiFileEntrySign {

    public static final String PATH_UPLOAD = "/open-api/file/upload";

    private OpenApiFileEntrySign() {}

    public static byte[] signUpload(FileEntryOpenApiUploadRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_UPLOAD, request, clientSecret);
    }
}
