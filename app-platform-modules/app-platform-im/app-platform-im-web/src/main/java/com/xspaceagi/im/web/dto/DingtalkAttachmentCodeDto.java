package com.xspaceagi.im.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 钉钉附件下载码信息，含原始文件名（可选）用于保留扩展名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DingtalkAttachmentCodeDto {

    private String downloadCode;

    private boolean picture;

    /** 原始文件名，如 report.pdf，有则优先用其扩展名 */
    private String originalFileName;
}
