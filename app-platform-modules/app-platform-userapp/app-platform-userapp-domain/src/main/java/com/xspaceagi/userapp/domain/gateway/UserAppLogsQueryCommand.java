package com.xspaceagi.userapp.domain.gateway;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAppLogsQueryCommand {

    /** 增量拉取游标（上次 query 响应返回的 cursor，支持断点续拉） */
    private String cursor;

    /** 关键字过滤（子串匹配） */
    private String keyword;

    /** 日志级别过滤（如 WARN/ERROR；空 = 不过滤） */
    private List<String> levels;

    /** 服务/日志源选择器（空 = 全部服务全部源） */
    private List<LogSelector> selectors;

    /** 起始时间过滤（RFC3339） */
    private String since;

    /** 每源尾部行数限制（单源上限 10000） */
    private Integer tail;

    /** 结束时间过滤（RFC3339） */
    private String until;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogSelector {

        /** 服务 ID（manifest 中声明的 service 名，如 "api"、"web"） */
        private String serviceId;

        /** 日志源 ID 列表（空 = 该服务全部源） */
        private List<String> sourceIds;
    }
}
