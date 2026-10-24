package com.xspaceagi.compose.domain.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
public class TableHeaderVo {

    @JsonPropertyDescription(value = "原始表头名称，一定要与原始表头名称保持一致，用于匹配。例如：用户名")
    private String originName;

    @JsonPropertyDescription(value = "生成对应的英文名称，符合属性命名规范（如果本身就是英文且符合命名规范，则保持原始名称），例如：user_name")
    private String enName;
}
