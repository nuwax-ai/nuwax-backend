package com.xspaceagi.log.sdk.vo;

import com.xspaceagi.log.sdk.annotation.SearchField;
import com.xspaceagi.log.sdk.annotation.SearchIndex;
import com.xspaceagi.log.sdk.vo.SearchDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@SearchIndex(indexName = "req_log")
public class LogDocument extends SearchDocument {

    @Schema(description = "租户ID", hidden = true)
    private Long tenantId;

    @Schema(description = "日志ID, 不用展示，用于查询详情")
    private String id;

    @Schema(description = "请求唯一标识可以用于关联一次请求中所有相关的操作")
    @SearchField(keyword = true)
    private String requestId;

    @Schema(description = "日志产生对象所在的空间ID")
    private Long spaceId;

    @Schema(description = "请求发起的用户ID")
    private Long userId;

    @Schema(description = "用户名")
    @SearchField(keyword = true)
    private String userName;

    @Schema(description = "日志对象类型")
    @SearchField(keyword = true)
    private String targetType;

    @Schema(description = "日志对象名称")
    @SearchField(keyword = true)
    private String targetName;

    @Schema(description = "日志对象ID")
    @SearchField(keyword = true)
    private String targetId;

    @Schema(description = "会话ID")
    @SearchField(keyword = true)
    private String conversationId;

    @Schema(description = "输入参数")
    @SearchField(store = true)
    private String input;

    @Schema(description = "执行结果")
    @SearchField(store = true)
    private String output;

    @Schema(description = "执行过程数据")
    @SearchField(store = true)
    private String processData;

    @Schema(description = "缓存输入token数量")
    private Integer cacheInputToken;

    @Schema(description = "缓存创建输入token数量")
    private Integer cacheCreationInputToken;

    @Schema(description = "输入token数量")
    private Integer inputToken;

    @Schema(description = "输出token数量")
    private Integer outputToken;

    @Schema(description = "请求开始时间")
    private Long requestStartTime;

    @Schema(description = "请求结束时间")
    private Long requestEndTime;

    @Schema(description = "API Key")
    private String apiKey;

    @Schema(description = "执行结果码 0000为成功")
    @SearchField(keyword = true)
    private String resultCode;

    @Schema(description = "执行结果描述")
    @SearchField(store = true)
    private String resultMsg;

    @Schema(description = "日志产生时间")
    private Long createTime;

    @Schema(description = "日志产生来源", hidden = true)
    @SearchField(keyword = true)
    private String from;
}
