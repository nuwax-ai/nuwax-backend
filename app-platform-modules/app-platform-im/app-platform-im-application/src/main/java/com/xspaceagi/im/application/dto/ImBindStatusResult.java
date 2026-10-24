package com.xspaceagi.im.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImBindStatusResult {
    /** pending | authorized | bound | expired | failed */
    private String status;
    private String channel;
    private String displayName;
    private String message;
    /** status=authorized 时返回，供 App 轮询后调 bind/confirm；其他状态为 null */
    private String confirmToken;
}
