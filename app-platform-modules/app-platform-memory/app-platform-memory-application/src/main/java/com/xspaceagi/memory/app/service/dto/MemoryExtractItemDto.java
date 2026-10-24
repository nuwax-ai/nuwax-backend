package com.xspaceagi.memory.app.service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API Key 数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractItemDto {

    /**
     * 是否需要保存
     */
    @JsonPropertyDescription("判断是否包含可提取的记忆，有则需要保存")
    private Boolean shouldSave;

    /**
     * 一级分类
     */
    @JsonPropertyDescription("一级分类，严格按照提示词中的约定")
    private String category;

    /**
     * 二级分类
     */
    @JsonPropertyDescription("二级分类，严格按照提示词中的约定")
    private String subCategory;

    /**
     * 更新的数据
     */
    @JsonPropertyDescription("提取的记忆键值对，严格按照提示词中约定的键名")
    private List<KeyValueDto> keyValues;

    /**
     * 标签列表
     */
    @JsonPropertyDescription("关键词标签列表，分词可以细粒度一点，尽量多生成有助于更精确查找，根据上下文以及用户消息提取或扩展标签")
    private List<String> tags;

    /**
     * 是否敏感信息
     */
    @JsonPropertyDescription("判断是否包含敏感信息")
    private Boolean isSensitive;

    //priority 范围 0-10，数字越大优先级越高
    @JsonPropertyDescription("优先级，范围 0-10，数字越大优先级越高")
    private Integer priority;

    /**
     * 更新数据内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyValueDto {

        /**
         * 键名
         */
        @JsonPropertyDescription("键名，尽量使用已定义的键名")
        private String key;

        /**
         * 值
         */
        @JsonPropertyDescription("键值（对应的记忆值）")
        private String value;
    }

    public Map<String, String> toMap() {
        if (keyValues == null) {
            return new HashMap<>();
        }
        return keyValues.stream().collect(Collectors.toMap(KeyValueDto::getKey, KeyValueDto::getValue, (k1, k2) -> k1));
    }
}
