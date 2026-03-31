package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class GetUploadUrlReq {
    private String filekey;
    @JSONField(name = "media_type")
    private Integer mediaType;
    @JSONField(name = "to_user_id")
    private String toUserId;
    @JSONField(name = "rawsize")
    private Long rawsize;
    @JSONField(name = "rawfilemd5")
    private String rawfilemd5;
    @JSONField(name = "filesize")
    private Long filesize;
    @JSONField(name = "thumb_rawsize")
    private Long thumbRawsize;
    @JSONField(name = "thumb_rawfilemd5")
    private String thumbRawfilemd5;
    @JSONField(name = "thumb_filesize")
    private Long thumbFilesize;
    @JSONField(name = "no_need_thumb")
    private Boolean noNeedThumb;
    private String aeskey;
}
