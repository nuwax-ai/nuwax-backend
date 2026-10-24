package com.xspaceagi.agent.core.infra.speech.tts;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;

import java.util.ArrayList;
import java.util.List;

public interface TtsService {

    /**
     * 打开一条 TTS 合成会话。Agent 流式出字时必须复用同一条 {@link TtsStream}，
     * 通过 {@link TtsStream#feed} 分段送入文本。
     */
    TtsStream openStream(AudioParams audioParams);

    /**
     * 一次性合成整段文本的 PCM16 帧。内部会 open → feed → flush → close。
     * 流式场景请使用 {@link #openStream(AudioParams)}。
     */
    default List<byte[]> synthesizePcm16Frames(String text, AudioParams audioParams) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        audioParams.requireValid();
        TtsStream stream = openStream(audioParams);
        try {
            List<byte[]> frames = new ArrayList<>();
            frames.addAll(stream.feed(text, false));
            frames.addAll(stream.feed("", true));
            return frames;
        } finally {
            stream.close();
        }
    }
}
