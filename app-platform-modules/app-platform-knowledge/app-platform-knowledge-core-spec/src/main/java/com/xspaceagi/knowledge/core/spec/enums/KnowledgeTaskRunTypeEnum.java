package com.xspaceagi.knowledge.core.spec.enums;

import lombok.Getter;

/**
 * 任务重试阶段类型:1:文档分段;15:全文检索同步;2:生成Q&A;3:生成嵌入;10:任务完毕
 */
@Getter
public enum KnowledgeTaskRunTypeEnum {
    SEGMENT(1, "文档分段"),
    FULLTEXT_SYNC(15, "全文检索同步"),
    QA(2, "生成Q&A"),
    EMBEDDING(3, "生成嵌入"),
    GRAPH(4, "知识库"),
    SUCCESS(10, "任务完毕");

    KnowledgeTaskRunTypeEnum(int type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    private final int type;
    private final String desc;


    public static KnowledgeTaskRunTypeEnum getByType(int type) {
        for (KnowledgeTaskRunTypeEnum value : KnowledgeTaskRunTypeEnum.values()) {
            if (value.getType() == type) {
                return value;
            }
        }
        return null;
    }

}
