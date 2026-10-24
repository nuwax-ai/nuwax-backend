package com.xspaceagi.memory.app.service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class MemoryMergeResultDto {

    @JsonPropertyDescription("更新、新增、追加memoryId对应记忆中的键值对")
    private List<MergeUnitWithID> updateMemoryKeyValues;

    @JsonPropertyDescription("生成新的记忆")
    private List<MergeUnit> insertMemories;

    @JsonPropertyDescription("旧记忆已被新事实完全取代、没有任何保留价值时，返回需要软删的记忆ID列表")
    private List<Long> deleteMemoryIds;

    @Data
    public static class MergeUnitWithID {
        @JsonPropertyDescription("记忆ID")
        private Long memoryId;
        @JsonPropertyDescription("更新（替换原有内容，比如手机号变更等）、新增、追加（在原有内容后面追加新的内容，比如之前个人形象身高180，现在又获取到了新的数据为皮肤黝黑，新内容为“身高180，皮肤黝黑”）对应记忆中的键值对")
        private Map<String, String> keyValues;
        @JsonPropertyDescription("关键词标签列表")
        private List<String> tags;
        @JsonPropertyDescription("是否为敏感信息")
        private Boolean isSensitive;
    }

    @Data
    public static class MergeUnit {
        @JsonPropertyDescription("一级分类")
        private String category;
        @JsonPropertyDescription("二级分类")
        private String subCategory;
        @JsonPropertyDescription("关键词标签列表")
        private List<String> tags;
        @JsonPropertyDescription("记忆中的键值对")
        private Map<String, String> keyValues;
        @JsonPropertyDescription("是否为敏感信息")
        private Boolean isSensitive;
    }
}
