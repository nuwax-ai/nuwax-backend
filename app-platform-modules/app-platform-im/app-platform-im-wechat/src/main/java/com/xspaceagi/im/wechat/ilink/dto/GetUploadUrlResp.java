package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class GetUploadUrlResp {
    @JSONField(name = "upload_param")
    private String uploadParam;
    @JSONField(name = "thumb_upload_param")
    private String thumbUploadParam;
}
