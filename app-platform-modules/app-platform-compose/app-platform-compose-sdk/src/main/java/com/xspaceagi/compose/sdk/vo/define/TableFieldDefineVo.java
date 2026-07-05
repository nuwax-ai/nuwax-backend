package com.xspaceagi.compose.sdk.vo.define;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Schema(description = "表字段定义")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TableFieldDefineVo {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    @JsonPropertyDescription("主键ID")
    private Long id;
    /**
     * 是否为系统字段,1:系统字段;-1:非系统字段
     */
    @Schema(description = "是否为系统字段,1:系统字段;-1:非系统字段")
    @JsonPropertyDescription("是否为系统字段,1:系统字段;-1:非系统字段")
    private Integer systemFieldFlag;

    /**
     * 字段名
     */
    @Schema(description = "字段名")
    @JsonPropertyDescription("字段名")
    private String fieldName;

    /**
     * 字段描述
     */
    @Schema(description = "字段描述")
    @JsonPropertyDescription("字段描述")
    private String fieldDescription;

    /**
     * 字段类型：1:String;2:Integer;3:Number;4:Boolean;5:Date
     */
    @Schema(description = "字段类型：1:String;2:Integer;3:Number;4:Boolean;5:Date")
    @JsonPropertyDescription("字段类型：1:String;2:Integer;3:Number;4:Boolean;5:Date")
    private Integer fieldType;

    /**
     * 是否可为空：1-可空 -1-非空
     */
    @Schema(description = "是否可为空：1-可空 -1-非空")
    @JsonPropertyDescription("是否可为空：1-可空 -1-非空")
    private Integer nullableFlag;

    /**
     * 默认值
     */
    @Schema(description = "默认值")
    @JsonPropertyDescription("默认值")
    private String defaultValue;

    /**
     * 是否唯一：1-唯一 -1-非唯一
     */
    @Schema(description = "是否唯一：1-唯一 -1-非唯一")
    @JsonPropertyDescription("是否唯一：1-唯一 -1-非唯一")
    private Integer uniqueFlag;

    @Schema(description = "是否启用：1-启用 -1-禁用")
    @JsonPropertyDescription("是否启用：1-启用 -1-禁用")
    private Integer enabledFlag;

    /**
     * 字段顺序
     */
    @Schema(description = "字段顺序")
    @JsonPropertyDescription("字段顺序")
    private Integer sortIndex;

}
