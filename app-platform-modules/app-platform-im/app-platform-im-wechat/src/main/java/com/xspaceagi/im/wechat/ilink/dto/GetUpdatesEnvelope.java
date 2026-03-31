package com.xspaceagi.im.wechat.ilink.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetUpdatesEnvelope {
    @Builder.Default
    @JSONField(name = "get_updates_buf")
    private String getUpdatesBuf = "";
    @JSONField(name = "base_info")
    private BaseInfo baseInfo;
}
