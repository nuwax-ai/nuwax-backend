package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class ImageItem {
    private CdnMedia media;
    @JSONField(name = "thumb_media")
    private CdnMedia thumbMedia;
    private String aeskey;
    private String url;
    @JSONField(name = "mid_size")
    private Integer midSize;
    @JSONField(name = "thumb_size")
    private Integer thumbSize;
    @JSONField(name = "thumb_height")
    private Integer thumbHeight;
    @JSONField(name = "thumb_width")
    private Integer thumbWidth;
    @JSONField(name = "hd_size")
    private Integer hdSize;
}
