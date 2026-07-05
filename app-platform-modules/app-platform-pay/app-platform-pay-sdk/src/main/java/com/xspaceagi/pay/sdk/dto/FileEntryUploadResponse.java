package com.xspaceagi.pay.sdk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileEntryUploadResponse {
    /** 32 位小写 hex，无中划线 */
    private String fileKey;
    /** 对外可访问 URL（含 site.base-url） */
    private String publicUrl;
    private String contentType;
    private int sizeBytes;
}
