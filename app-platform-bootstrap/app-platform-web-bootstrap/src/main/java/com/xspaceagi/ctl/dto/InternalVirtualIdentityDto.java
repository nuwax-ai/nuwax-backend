package com.xspaceagi.ctl.dto;

import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部接口：虚拟身份对外（server-to-server）契约。
 * <p>刻意不复用 system 模块的 {@code VirtualIdentityDto}：内部 API 的对外契约独立演进，
 * 只暴露调用方需要的字段（如 status 按枚举名下发的 String，而非枚举本身）。
 */
@Data
@Schema(description = "虚拟身份")
public class InternalVirtualIdentityDto {

    @Schema(description = "身份用户 id：后续以此 id 作为主体 id 参与记忆/会话/模型调用等链路")
    private Long userId;

    private String uid;

    private Long tenantId;

    @Schema(description = "账号类型：agent / group")
    private String accountType;

    @Schema(description = "身份关联标识")
    private String principalRefId;

    private String nickName;

    private String avatar;

    @Schema(description = "状态，恒为 Enabled")
    private String status;

    @Schema(description = "true=本次新建；false=命中已存在的身份（幂等复用）")
    private Boolean newlyCreated;

    @Schema(description = "该身份的创建时间")
    private Date createTime;
}
