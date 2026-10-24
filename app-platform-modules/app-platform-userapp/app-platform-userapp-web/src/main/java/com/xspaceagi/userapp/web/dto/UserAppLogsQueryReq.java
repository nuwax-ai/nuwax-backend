package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "应用日志查询请求")
public class UserAppLogsQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long appId;

    @Schema(description = "环境：dev 开发环境（默认）；prod 发布环境")
    private String env;

    @Schema(description = "增量拉取游标（上次响应返回的 cursor，支持断点续拉）")
    private String cursor;

    @Schema(description = "关键字过滤（子串匹配）")
    private String keyword;

    @Schema(description = "日志级别过滤，如 [\"WARN\",\"ERROR\"]；空 = 不过滤")
    private List<String> levels;

    @Schema(description = "服务/日志源选择器；空 = 全部服务全部源")
    private List<Selector> selectors;

    @Schema(description = "起始时间过滤（RFC3339）")
    private String since;

    @Schema(description = "每源尾部行数限制（单源上限 10000）")
    private Integer tail;

    @Schema(description = "结束时间过滤（RFC3339）")
    private String until;

    @Data
    @Schema(description = "服务/日志源选择器")
    public static class Selector implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "服务ID（manifest 中声明的 service 名，如 \"api\"、\"web\"）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String serviceId;

        @Schema(description = "日志源 ID 列表（空 = 该服务全部源）")
        private List<String> sourceIds;
    }
}
