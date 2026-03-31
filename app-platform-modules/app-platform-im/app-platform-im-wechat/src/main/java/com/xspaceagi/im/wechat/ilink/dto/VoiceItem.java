package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class VoiceItem {
    private CdnMedia media;
    @JSONField(name = "encode_type")
    private Integer encodeType;
    @JSONField(name = "bits_per_sample")
    private Integer bitsPerSample;
    @JSONField(name = "sample_rate")
    private Integer sampleRate;
    private Integer playtime;
    private String text;
}
