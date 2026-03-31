package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class FileItem {
    private CdnMedia media;
    @JSONField(name = "file_name")
    private String fileName;
    private String md5;
    private String len;
}
