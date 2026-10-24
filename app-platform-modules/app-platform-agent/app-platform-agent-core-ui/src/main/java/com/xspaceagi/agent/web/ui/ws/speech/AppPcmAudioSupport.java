package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.protocol.NaConstants;
import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;

/**
 * App PCM16 音频辅助：构造 AudioParams / 包装 NaFrame。
 */
final class AppPcmAudioSupport {

    static final int DEFAULT_SAMPLE_RATE = 16000;
    static final int DEFAULT_FRAME_MS = 20;

    private AppPcmAudioSupport() {
    }

    static AudioParams pcm16Params(int sampleRate, int frameDurationMs) {
        int rate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
        int frameMs = frameDurationMs > 0 ? frameDurationMs : DEFAULT_FRAME_MS;
        AudioParams params = new AudioParams();
        params.setInputFormat("pcm16");
        params.setCodec("pcm16");
        params.setOpus(false);
        params.setOutputFormat("pcm16");
        params.setSampleRate(rate);
        params.setOutputSampleRate(rate);
        params.setChannels(1);
        params.setMicChannels(1);
        params.setFrameDurationMs(frameMs);
        params.requireValid();
        return params;
    }

    static NaFrame pcm16Frame(byte[] payload, int sampleRate, int frameDurationMs, long sequence) {
        return new NaFrame(
                NaConstants.VERSION,
                NaConstants.CODEC_PCM16,
                0,
                1,
                sampleRate,
                frameDurationMs,
                sequence,
                System.currentTimeMillis(),
                payload);
    }
}
