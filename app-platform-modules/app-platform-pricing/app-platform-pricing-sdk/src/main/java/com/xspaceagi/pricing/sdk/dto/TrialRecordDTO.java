package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@Schema(description = "试用次数记录")
public class TrialRecordDTO implements Serializable {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "业务类型")
    private TargetTypeEnum targetType;

    @Schema(description = "业务对象ID")
    private String targetId;

    @Schema(description = "已使用次数")
    private Integer usedCount;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;
}
