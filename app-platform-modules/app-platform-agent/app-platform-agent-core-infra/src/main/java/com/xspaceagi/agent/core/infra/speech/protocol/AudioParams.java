package com.xspaceagi.agent.core.infra.speech.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioParams {

    @JsonProperty("input_format")
    private String inputFormat;

    private String codec;

    private boolean opus;

    @JsonProperty("output_format")
    private String outputFormat;

    @JsonProperty("sample_rate")
    private int sampleRate;

    @JsonProperty("output_sample_rate")
    private int outputSampleRate;

    private int channels;

    @JsonProperty("mic_channels")
    private int micChannels;

    @JsonProperty("frame_duration_ms")
    private int frameDurationMs;

    /**
     * 上行采样率，以 hello.audio_params.sample_rate 为准。
     */
    public int uplinkSampleRate() {
        requireValid();
        return sampleRate;
    }

    /**
     * 下行 PCM16 采样率，以 hello.audio_params.output_sample_rate 为准。
     */
    public int downlinkSampleRate() {
        requireValid();
        return outputSampleRate > 0 ? outputSampleRate : sampleRate;
    }

    /**
     * 帧时长，以 hello.audio_params.frame_duration_ms 为准。
     */
    public int frameDurationMs() {
        requireValid();
        return frameDurationMs;
    }

    /**
     * 上行是否 Opus，以 hello 声明的 codec / opus 为准。
     */
    public boolean uplinkUsesOpus() {
        requireValid();
        return opus || "opus".equalsIgnoreCase(codec);
    }

    public boolean uplinkUsesPcm16() {
        return !uplinkUsesOpus();
    }

    /**
     * 单帧下行 PCM16 mono 字节数，由设备 hello 参数推导。
     */
    public int downlinkPcm16FrameBytes() {
        return downlinkSampleRate() * frameDurationMs() / 1000 * 2;
    }

    public void requireValid() {
        if (sampleRate <= 0) {
            throw new IllegalStateException("hello.audio_params.sample_rate is required");
        }
        int downlink = outputSampleRate > 0 ? outputSampleRate : sampleRate;
        if (downlink <= 0) {
            throw new IllegalStateException("hello.audio_params.output_sample_rate is required");
        }
        if (frameDurationMs <= 0) {
            throw new IllegalStateException("hello.audio_params.frame_duration_ms is required");
        }
        if (codec == null || codec.isBlank()) {
            throw new IllegalStateException("hello.audio_params.codec is required");
        }
    }

}
