package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.infra.dao.entity.SensitiveWord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "敏感词检测结果")
public class SensitiveWordCheckResultDto {

    @Schema(description = "原文")
    private String text;

    @Schema(description = "处理后的文本")
    private String filteredText;

    @Schema(description = "是否命中断开连接策略")
    private Boolean disconnect;

    @Schema(description = "命中的敏感词列表")
    private List<SensitiveWord> hits;
}
