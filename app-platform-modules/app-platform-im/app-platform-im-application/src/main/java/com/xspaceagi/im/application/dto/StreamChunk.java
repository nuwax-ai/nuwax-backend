package com.xspaceagi.im.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式输出数据块
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamChunk {

    /**
     * 当前累积的文本内容
     */
    private String text;

    /**
     * 是否为最终结果
     */
    private boolean isFinal;

    /**
     * 会话 ID
     */
    private Long conversationId;

    public StreamChunk(String text, boolean isFinal) {
        this.text = text;
        this.isFinal = isFinal;
        this.conversationId = null;
    }
}
