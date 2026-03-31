package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class VideoItem {
    private CdnMedia media;
    @JSONField(name = "video_size")
    private Integer videoSize;
    @JSONField(name = "play_length")
    private Integer playLength;
    @JSONField(name = "video_md5")
    private String videoMd5;
    @JSONField(name = "thumb_media")
    private CdnMedia thumbMedia;
    @JSONField(name = "thumb_size")
    private Integer thumbSize;
    @JSONField(name = "thumb_height")
    private Integer thumbHeight;
    @JSONField(name = "thumb_width")
    private Integer thumbWidth;
}
