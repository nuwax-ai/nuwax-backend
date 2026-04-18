package com.xspaceagi.compose.sdk.vo.define;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "表结构定义,新增数据表")
@Getter
@Setter
@Builder
public class CreateTableDefineVo {

//    /**
//     * 租户ID
//     */
//    @Schema(description = "租户ID")
//    @JsonPropertyDescription("租户ID")
//    private Long tenantId;
//
    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID")
    @JsonPropertyDescription("所属空间ID")
    private Long spaceId;

    /**
     * 图标图片地址
     */
    @Schema(description = "图标图片地址")
    @JsonPropertyDescription("图标图片地址")
    private String icon;

    /**
     * 表名
     */
    @Schema(description = "表名")
    @JsonPropertyDescription("表名")
    private String tableName;

    /**
     * 表描述
     */
    @Schema(description = "表描述")
    @JsonPropertyDescription("表描述")
    private String tableDescription;

    /**
     * 创建人id
     */
    @Schema(description = "创建人id")
    @JsonPropertyDescription("创建人id")
    @NotNull(message = "Creator ID is required")
    private Long creatorId;


    /**
     * 表下面的字段定义列表
     */
    @Schema(description = "表下面的字段定义列表")
    @JsonPropertyDescription("表下面的字段定义列表")
    private List<TableFieldDefineVo> fieldList;

    public static CreateTableDefineVo convert(TableDefineVo tableDefineVo) {
        return CreateTableDefineVo.builder()
                .spaceId(tableDefineVo.getSpaceId())
                .icon(tableDefineVo.getIcon())
                .tableName(tableDefineVo.getTableName())
                .tableDescription(tableDefineVo.getTableDescription())
                .creatorId(tableDefineVo.getCreatorId())
                .fieldList(tableDefineVo.getFieldList())
                .build();
    }
}
