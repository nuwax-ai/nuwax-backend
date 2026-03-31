package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class ApiInvokeStatDto {

    @Schema(description = "接口名称")
    private String name;
    @Schema(description = "接口路径")
    private String path;
    @Schema(description = "接口标识，查询调用记录时from参数传改值")
    private String key;
    @Schema(description = "接口调用总次数")
    private InvokeCount total;
    @Schema(description = "接口调用今日次数")
    private InvokeCount today;
    @Schema(description = "接口调用昨日次数")
    private InvokeCount yesterday;
    @Schema(description = "接口调用本周次数")
    private InvokeCount week;
    @Schema(description = "接口调用本月次数")
    private InvokeCount month;


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class InvokeCount {
        private Long totalCount;
        private Long successCount;
        private Long failCount;
    }
}
