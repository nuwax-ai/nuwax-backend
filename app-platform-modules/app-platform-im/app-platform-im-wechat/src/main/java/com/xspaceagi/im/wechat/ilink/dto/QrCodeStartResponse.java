package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class QrCodeStartResponse {
    private String qrcode;
    @JSONField(name = "qrcode_img_content")
    private String qrcodeImgContent;
}
