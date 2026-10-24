package com.xspaceagi.agent.core.adapter.dto.recommend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐对象的可选提示词条目。
 * 以对象数组形式存入 target_recommend.prompts（JSON 列），由 RecommendPromptListTypeHandler 序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendPrompt {

    /** 展示标题 */
    private String title;

    /** 提示词正文内容 */
    private String content;

    /** 图标地址 */
    private String icon;
}
