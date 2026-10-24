package com.xspaceagi.agent.core.infra.speech.asr;

/**
 * 流式 ASR 定稿句；时间戳相对<strong>本 ASR 会话</strong>音频起点（毫秒）。
 */
public record AsrSentence(
        String text,
        Integer beginMs,
        Integer endMs) {
}
