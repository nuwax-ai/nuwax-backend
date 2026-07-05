package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateInfoAiDto implements Serializable {

    @JsonPropertyDescription("项目名称，简洁有意义，不超过20个字符")
    private String name;

    @JsonPropertyDescription("项目描述，一句话概括功能和用途，不超过100个字符")
    private String description;

    @JsonPropertyDescription("SVG图标内容，根元素必须为 <svg width=\"200\" height=\"200\" viewBox=\"0 0 100 100\" xmlns=\"http://www.w3.org/2000/svg\">；第一个元素必须是铺满画布的背景 rect（x=0,y=0,width=100,height=100）；主图形需占满大部分画布（约10-90坐标范围），不要留大空白边距；不使用外部资源")
    private String svgIcon;
}
