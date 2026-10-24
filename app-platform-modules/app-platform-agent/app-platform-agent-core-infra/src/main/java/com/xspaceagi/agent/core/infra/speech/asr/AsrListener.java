package com.xspaceagi.agent.core.infra.speech.asr;

/**
 * 流式 ASR 回调。定稿句用于实时字幕；partial 为中间结果。
 */
public interface AsrListener {

    /** 未定稿中间结果。 */
    default void onPartial(String text) {
    }

    /** 服务端 VAD 判定一句结束（如 sentence_end / definite）。 */
    default void onSentenceFinal(AsrSentence sentence) {
    }

    default void onError(String error) {
    }
}
