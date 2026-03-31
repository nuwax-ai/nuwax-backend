package com.xspaceagi.im.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "微信 iLink 扫码状态")
public class ImWechatIlinkQrPollResponse {
    @Schema(description = "wait / scaned / confirmed / expired；落库请使用 POST /api/im-config/channel/add")
    private String status;

    @Schema(description = "仅 status=confirmed 时可能返回；与落库后 config_data 同结构的 JSON 预览（会话内 botToken/ilinkBotId 等齐全时才有值；含敏感字段，请妥善保管）")
    private String configData;
}
