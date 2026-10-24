package com.xspaceagi.im.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImUserBindingDto {
    private String channel;
    private Long userId;
    private String imUserId;
    private String displayName;
    private Long channelConfigId;
    private String source;
    private boolean bound;
}
