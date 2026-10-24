package com.xspaceagi.compose.domain.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

@Data
public class TableHeadersVo {

    @JsonPropertyDescription(value = "翻译后的表头列表，与原始数据一一对应")
    private List<TableHeaderVo> headers;
}
