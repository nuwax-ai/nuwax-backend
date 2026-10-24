package com.xspaceagi.agent.core.infra.speech.audio;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 无 Opus 解码能力时的占位实现，仅用于测试或手动装配。
 */
public class UnsupportedOpusDecoder implements OpusDecoder {

    private static final Logger log = LoggerFactory.getLogger(UnsupportedOpusDecoder.class);

    @Override
    public byte[] decodePacket(byte[] opusPacket, AudioParams audioParams) {
        log.warn("Opus uplink packet received ({} bytes) but no Opus decoder is configured; packet discarded",
                opusPacket == null ? 0 : opusPacket.length);
        return new byte[0];
    }
}
