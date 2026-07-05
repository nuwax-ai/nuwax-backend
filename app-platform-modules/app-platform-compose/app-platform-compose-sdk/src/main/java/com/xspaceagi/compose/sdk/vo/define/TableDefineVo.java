package com.xspaceagi.compose.sdk.vo.define;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "表结构定义")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TableDefineVo {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    @JsonPropertyDescription("主键ID")
    private Long id;

    /**
     * 租户ID
     */
    @Schema(description = "租户ID")
    @JsonPropertyDescription("租户ID")
    private Long tenantId;

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
     * Doris数据库名
     */
    @Schema(description = "Doris数据库名")
    @JsonPropertyDescription("Doris数据库名")
    private String dorisDatabase;

    /**
     * Doris表名
     */
    @Schema(description = "Doris表名")
    @JsonPropertyDescription("Doris表名")
    private String dorisTable;



     /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @JsonPropertyDescription("创建时间")
    private LocalDateTime created;

    /**
     * 创建人id
     */
    @Schema(description = "创建人id")
    @JsonPropertyDescription("创建人id")
    private Long creatorId;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    @JsonPropertyDescription("创建人")
    private String creatorName;

    /**
     * 创建人昵称
     */
    @Schema(description = "创建人昵称")
    @JsonPropertyDescription("创建人昵称")
    private String creatorNickName;

    /**
     * 创建人头像
     */
    @Schema(description = "创建人头像")
    @JsonPropertyDescription("创建人头像")
    private String creatorAvatar;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @JsonPropertyDescription("更新时间")
    private LocalDateTime modified;

    /**
     * 最后修改人id
     */
    @Schema(description = "最后修改人id")
    @JsonPropertyDescription("最后修改人id")
    private Long modifiedId;

    /**
     * 最后修改人
     */
    @Schema(description = "最后修改人")
    @JsonPropertyDescription("最后修改人")
    private String modifiedName;

    /**
     * 表下面的字段定义列表
     */
    @Schema(description = "表下面的字段定义列表")
    @JsonPropertyDescription("表下面的字段定义列表")
    private List<TableFieldDefineVo> fieldList;


    @Schema(description = "原始建表DDL")
    @JsonPropertyDescription("原始建表DDL")
    private String createTableDdl;
}
