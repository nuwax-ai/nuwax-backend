package com.xspaceagi.agent.core.infra.speech.audio;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Concentus 的 Opus packet 解码，输出 PCM16 mono LE。
 */
@Primary
@Component
public class ConcentusOpusDecoder implements com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder {

    private static final Logger log = LoggerFactory.getLogger(ConcentusOpusDecoder.class);
    private static final int MAX_FRAME_MS = 120;

    private final Map<DecoderKey, OpusDecoder> decoders = new ConcurrentHashMap<>();

    @Override
    public byte[] decodePacket(byte[] opusPacket, AudioParams audioParams) {
        if (opusPacket == null || opusPacket.length == 0) {
            return new byte[0];
        }
        audioParams.requireValid();
        int sampleRate = audioParams.uplinkSampleRate();
        int channels = uplinkChannels(audioParams);
        if (channels != 1) {
            log.warn("Opus uplink with {} channels is not supported; packet discarded", channels);
            return new byte[0];
        }
        try {
            OpusDecoder decoder = decoders.computeIfAbsent(new DecoderKey(sampleRate, channels), key -> {
                try {
                    return new OpusDecoder(sampleRate, channels);
                } catch (OpusException ex) {
                    throw new IllegalStateException("failed to create Opus decoder", ex);
                }
            });
            int maxSamples = sampleRate * MAX_FRAME_MS / 1000;
            short[] pcm = new short[maxSamples * channels];
            int decodedSamples = decoder.decode(opusPacket, 0, opusPacket.length, pcm, 0, maxSamples, false);
            if (decodedSamples <= 0) {
                return new byte[0];
            }
            return shortsToPcm16Le(pcm, decodedSamples * channels);
        } catch (Exception ex) {
            log.debug("Opus decode failed ({} bytes, {}Hz): {}", opusPacket.length, sampleRate, ex.getMessage());
            return new byte[0];
        }
    }

    private static int uplinkChannels(AudioParams audioParams) {
        if (audioParams.getMicChannels() > 0) {
            return audioParams.getMicChannels();
        }
        if (audioParams.getChannels() > 0) {
            return audioParams.getChannels();
        }
        return 1;
    }

    private static byte[] shortsToPcm16Le(short[] samples, int count) {
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            pcm[i * 2] = (byte) (samples[i] & 0xFF);
            pcm[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return pcm;
    }

    private record DecoderKey(int sampleRate, int channels) {
    }
}
