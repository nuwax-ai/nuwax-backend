package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

/** Open API 文件上传元数据（不含文件二进制；与 multipart 表单字段一致）。 */
@Data
public class FileEntryOpenApiUploadRequest {
    private String clientId;
    private String bizType;
    private String replaceFileKey;
    private Long timestamp;
    private String nonce;
    private String signature;
}
