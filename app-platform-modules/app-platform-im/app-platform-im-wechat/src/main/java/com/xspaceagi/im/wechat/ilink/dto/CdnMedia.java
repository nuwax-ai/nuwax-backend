package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class CdnMedia {
    @JSONField(name = "encrypt_query_param")
    private String encryptQueryParam;
    @JSONField(name = "aes_key")
    private String aesKey;
    @JSONField(name = "encrypt_type")
    private Integer encryptType;
}
