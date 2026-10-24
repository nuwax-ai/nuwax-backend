package com.xspaceagi.custompage.infra.vo;

import com.xspaceagi.custompage.sdk.dto.PublishTypeEnum;
import lombok.Data;

@Data
public class BackendVo {

    private String scheme;
    private String host;
    private Integer port;
    private String uri;
    private boolean requireAuth;
    private PublishTypeEnum publishType;
    private Long devAgentId;
    /** 沙箱直连 backend 的 x-api-key（仅动态解析出的沙箱 backend 携带；其他 backend 为 null，不下发该头） */
    private String apiKey;
}
