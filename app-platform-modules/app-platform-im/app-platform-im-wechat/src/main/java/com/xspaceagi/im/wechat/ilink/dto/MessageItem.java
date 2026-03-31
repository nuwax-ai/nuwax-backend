package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class MessageItem {
    private Integer type;
    @JSONField(name = "create_time_ms")
    private Long createTimeMs;
    @JSONField(name = "update_time_ms")
    private Long updateTimeMs;
    @JSONField(name = "is_completed")
    private Boolean completed;
    @JSONField(name = "msg_id")
    private String msgId;
    @JSONField(name = "ref_msg")
    private RefMessage refMsg;
    @JSONField(name = "text_item")
    private TextItem textItem;
    @JSONField(name = "image_item")
    private ImageItem imageItem;
    @JSONField(name = "voice_item")
    private VoiceItem voiceItem;
    @JSONField(name = "file_item")
    private FileItem fileItem;
    @JSONField(name = "video_item")
    private VideoItem videoItem;
}
