package com.xspaceagi.im.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImBindStartResult {
    private String bindId;
    private String authorizeUrl;
    private String channel;
    /** 给客户端展示的打开授权提示（尤其企微需在企微内打开） */
    private String openHint;
}
