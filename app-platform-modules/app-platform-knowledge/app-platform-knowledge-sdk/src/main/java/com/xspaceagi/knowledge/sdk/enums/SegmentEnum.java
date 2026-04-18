package com.xspaceagi.knowledge.sdk.enums;

import lombok.Getter;

/**
 * 分段类型，words: 按照词数分段, delimiter: 按照分隔符分段，field: 按照字段分段,json文件,按照定义好的问答字段,进行解析问答结果
 */
@Getter
public enum SegmentEnum {
    WORDS,
    DELIMITER,
    SMART
}