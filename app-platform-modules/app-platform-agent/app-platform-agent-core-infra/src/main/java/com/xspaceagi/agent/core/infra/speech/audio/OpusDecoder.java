package com.xspaceagi.agent.core.infra.speech.audio;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;

public interface OpusDecoder {

    /**
     * 解码单个 Opus packet 为 PCM16 mono。采样率以 hello.audio_params.sample_rate 为准。
     */
    byte[] decodePacket(byte[] opusPacket, AudioParams audioParams);
}
