package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OAuth 授权回调处理结果（结构化）。
 * <p>
 * 判成败只看 {@code success}：失败时 {@code message} 是可直接展示给用户的中文原因
 * （state 无效/已使用、用户不一致、token 交换失败……），不需要再解析文案。
 */
@Schema(description = "OAuth 授权回调处理结果")
public record OauthExchangeVo(boolean success,
                              String service,
                              String displayName,
                              String message,
                              @Schema(description = "成功时返回新建的连接 id，失败为 null")
                              Long connectionId) {
}
