package com.xspaceagi.agent.core.infra.modelproviders.vo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 模型供应商配置
 */
@I18n(module = "ModelProvider")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelProviderVo {

    /** 租户ID */
    private Long tenantId;

    /** 供应商标识（如 nuwax、alibaba-cn） */
    @I18nField(keyPrefix = true)
    private String pid;

    /** 供应商名称（如 Nuwax、Aliyun） */
    private String name;

    /** 供应商图标URL */
    private String icon;

    /** API 端点信息 */
    private ApiInfo apiInfo;

    /** 文档地址 */
    private String doc;

    /** 模型列表 */
    private List<ModelInfo> models;

    /**
     * API 端点信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApiInfo {

        /** OpenAI 兼容接口地址 */
        @JSONField(name = "OpenAI")
        private String openAI;

        /** Anthropic 兼容接口地址 */
        @JSONField(name = "Anthropic")
        private String anthropic;

        /**
         * 从 API 地址 Map 构建（兼容 JSON 反序列化）
         */
        public static ApiInfo fromMap(Map<String, String> api) {
            if (api == null) return null;
            return ApiInfo.builder()
                    .openAI(api.getOrDefault("OpenAI", ""))
                    .anthropic(api.getOrDefault("Anthropic", ""))
                    .build();
        }
    }

    /**
     * 模型信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ModelInfo {

        /** 模型ID（如 qwen-max、deepseek-chat） */
        private String id;

        /** 模型显示名称 */
        private String name;

        /** 发布时间 */
        @JSONField(name = "release_date")
        private String releaseDate;

        // ====== 布尔属性 ======

        /** 是否支持附件 */
        private Boolean attachment;

        /** 是否推理模型 */
        private Boolean reasoning;

        /** 是否支持温度调节 */
        private Boolean temperature;

        /** 是否支持工具调用 */
        @JSONField(name = "tool_call")
        private Boolean toolCall;

        /** 是否支持结构化输出 */
        @JSONField(name = "structured_output")
        private Boolean structuredOutput;

        /** 知识截止日期 */
        private String knowledge;

        /** 交错推理字段配置 */
        private InterleavedInfo interleaved;

        /** 上下文/输出长度限制 */
        private ModelLimit limit;

        /** 输入/输出模态 */
        private ModelModalities modalities;
    }

    /**
     * 交错推理配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InterleavedInfo {

        /** 推理内容字段名（如 reasoning_content） */
        private String field;
    }

    /**
     * Token 限制
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ModelLimit {

        /** 上下文窗口大小 */
        private Integer context;

        /** 最大输出长度 */
        private Integer output;
    }

    /**
     * 多模态支持
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ModelModalities {

        /** 支持的输入类型（如 text、image、video、pdf） */
        private List<String> input;

        /** 支持的输出类型（如 text） */
        private List<String> output;
    }
}