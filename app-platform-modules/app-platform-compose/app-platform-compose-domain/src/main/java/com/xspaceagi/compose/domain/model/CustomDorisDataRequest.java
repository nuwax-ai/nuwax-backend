package com.xspaceagi.compose.domain.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 查询表数据
 */
@Schema(description = "查询表数据")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Data
public class CustomDorisDataRequest {

    /**
     * 表ID
     */
    @Schema(description = "表ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonPropertyDescription("表ID")
    private Long tableId;

    /**
     * 页码
     */
    @Schema(description = "页码", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonPropertyDescription("页码")
    private Long pageNo;

    /**
     * 页大小
     */
    @Schema(description = "页大小", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonPropertyDescription("页大小")
    private Long pageSize;

}
