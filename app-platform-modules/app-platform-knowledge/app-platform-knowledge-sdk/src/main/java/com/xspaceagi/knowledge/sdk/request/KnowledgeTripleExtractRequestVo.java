package com.xspaceagi.knowledge.sdk.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 三元组提取请求VO
 *
 * @author system
 * @date 2026-04-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeTripleExtractRequestVo {

    /**
     * 待提取三元组的文本内容
     */
    private String text;

    private List<Long> knowledgeBaseIds;
}
