package com.xspaceagi.agent.web.ui.controller.dto.git;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Git tag 请求（创建/删除共用）")
public class GitTagReq extends GitBaseReq {

    @Schema(description = "标签名称")
    private String tagName;

    @Schema(description = "标签信息（创建时使用）")
    private String message;
}
