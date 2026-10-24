package com.xspaceagi.memory.app.service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

@Data
public class MemoryExtractDto {

    @JsonPropertyDescription("提取的记忆项列表")
    private List<MemoryExtractItemDto> items;
}
