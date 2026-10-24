package com.xspaceagi.ctl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部接口：创建（或复用）虚拟身份的请求体。
 * <p>tenantId 走 query 参数，与其它内部接口一致。
 */
@Data
@Schema(description = "虚拟身份创建请求")
public class InternalVirtualIdentityCreateRequest {

    @Schema(description = "账号类型：agent-智能体身份；group-群代理身份。不接受 user（本接口不签发自然人）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "group")
    private String accountType;

    @Schema(description = "身份关联标识，同租户内同类型下唯一。agent 传智能体 id；group 传群标识（如 channel:sessionKey）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "wework:wrOg8BAAAA")
    private String principalRefId;

    @Schema(description = "昵称，可空（空则按类型给默认名：智能体 / 群代理）", example = "市场部群代理")
    private String nickName;

    @Schema(description = "头像地址，可空")
    private String avatar;
}
