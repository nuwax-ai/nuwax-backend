package com.xspaceagi.agent.core.infra.speech.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * App 实时 ASR/TTS 配置（前缀 {@code speech}）。
 * 字段与 vox {@code vox.asr}/{@code vox.tts} 对齐，便于运维拷贝凭证。
 */
@Data
@ConfigurationProperties(prefix = "speech")
public class SpeechProperties {

    private Asr asr = new Asr();
    private Tts tts = new Tts();
    private AppWs app = new AppWs();

    @Data
    public static class AppWs {
        private String asrWebsocketPath = "/api/speech/asr/ws";
        private String ttsWebsocketPath = "/api/speech/tts/ws";
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    }

    @Data
    public static class Asr {
        private String provider = "dashscope";
        /**
         * 实时 ASR 共用静音断句阈值（毫秒）。
         * Volc {@code end-window-size-ms} / DashScope {@code max-sentence-silence-ms} ≤0 时回退到此值。
         */
        private int sentenceSilenceMs = 600;
        /**
         * 百炼等「空闲约 23s 无数据」超时后，是否静默换新 ASR 会话并继续听音。
         */
        private boolean timeoutSilentRebuild = true;
        /**
         * 同一听音内最多静默重建次数；识别出新的定稿句（真实进展）后额度自动重置，
         * 长听音偶发断连可持续恢复，仅连续无进展的硬故障才受此上限约束。
         */
        private int rebuildMaxAttempts = 3;
        /**
         * 重建空窗内上行 PCM 回放缓冲（毫秒）；减轻建连丢字。
         */
        private int rebuildReplayMs = 400;
        private Volc volc = new Volc();
        private DashScopeAsr dashscope = new DashScopeAsr();

        public int resolveSentenceSilenceMs() {
            return sentenceSilenceMs > 0 ? sentenceSilenceMs : 600;
        }
    }

    @Data
    public static class Volc {
        private String endpoint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
        private String apiKey = "";
        private String appKey = "";
        private String accessKey = "";
        private String resourceId = "volc.seedasr.sauc.duration";
        private int chunkMs = 200;
        /**
         * 强制判停静音阈值（毫秒），对应协议 {@code end_window_size}；≤0 时用 {@link Asr#sentenceSilenceMs}。
         * 协议最小 200ms。
         */
        private int endWindowSizeMs;
        /**
         * 配合 {@code end_window_size}：音频时长超过该值（毫秒）后才允许静音判停。
         * ≤0 时用 {@link #DEFAULT_FORCE_TO_SPEECH_TIME_MS}。不传时上游默认约 10s。
         */
        private int forceToSpeechTimeMs;
        private int connectTimeoutMs = 5000;
        private int sendTimeoutMs = 10000;
        private int resultTimeoutMs = 15000;

        public static final int MIN_END_WINDOW_SIZE_MS = 200;
        public static final int DEFAULT_FORCE_TO_SPEECH_TIME_MS = 200;

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasLegacyCredentials() {
            return appKey != null && !appKey.isBlank()
                    && accessKey != null && !accessKey.isBlank();
        }

        public int resolveEndWindowSizeMs(Asr asr) {
            int resolved = endWindowSizeMs > 0
                    ? endWindowSizeMs
                    : (asr == null ? 600 : asr.resolveSentenceSilenceMs());
            return Math.max(MIN_END_WINDOW_SIZE_MS, resolved);
        }

        public int resolveForceToSpeechTimeMs() {
            return forceToSpeechTimeMs > 0 ? forceToSpeechTimeMs : DEFAULT_FORCE_TO_SPEECH_TIME_MS;
        }
    }

    @Data
    public static class Tts {
        private String provider = "dashscope";
        private VolcTts volc = new VolcTts();
        private DashScopeTts dashscope = new DashScopeTts();
        /**
         * 流式可朗读文本合并：达到该字数（或句末/中文子句标点 ，、：）再喂 TTS。
         */
        private int batchMinChars = 8;
        /**
         * 合并缓冲最长等待（毫秒）；超时即使未满字数也刷出。
         */
        private int batchMaxHoldMs = 120;
        /**
         * start 后预热 TTS 连接。
         */
        private boolean warmupOnStart = true;
        /**
         * 下行限速：prebuffer 内可突发，之后相邻帧间隔 ≥ frame_duration_ms。
         * App WS 默认关闭：由客户端缓冲播放；服务端实时滴送易因网络抖动欠载卡顿。
         */
        private boolean downlinkPaceEnabled = false;
        /**
         * 起播突发填充（毫秒）；对齐常见播放器缓冲目标。
         */
        private int downlinkPrebufferMs = 800;
        /**
         * 已切帧待发 outbound 上限（毫秒）。
         */
        private int downlinkMaxBufferedMs = 2_000;
        /**
         * TTS 已合成、尚未进入 outbound 的本机 PCM 上限（毫秒）。
         */
        private int pendingPcmMaxBufferedMs = 60_000;
        /**
         * 距上次成功 feed 的最大空闲（毫秒）。到期即使 PCM/outbound 背压也继续喂字，
         * 避免百炼 ~23s 空闲超时。≤0 关闭保活。默认 12000。
         */
        private int feedMaxIdleMs = 12_000;
        /**
         * 主动保活文本：超过 {@link #feedMaxIdleMs} 仍无待喂文本时，向 provider 发送该文本
         * 以重置其空闲计时，避免百炼 ~23s 空闲断连。置空关闭主动保活。
         */
        private String feedKeepaliveText = "，";
        /**
         * 喂字空窗导致百炼 TTS ~23s 空闲超时后，是否静默换新会话并继续喂后续文本。
         */
        private boolean timeoutSilentRebuild = true;
        /**
         * 静默重建最大次数（单次 playback）；真实文本喂字成功后额度自动重置，
         * 长会话偶发断连可持续恢复，仅连续无进展的硬故障才受此上限约束。
         */
        private int rebuildMaxAttempts = 3;
    }

    @Data
    public static class DashScopeAsr {
        private String workspaceId = "";
        private String apiKey = "";
        private String region = "cn-beijing";
        /** 为空则按 workspace-id + region 自动拼接。 */
        private String endpoint = "";
        private String model = "fun-asr-realtime";
        private String format = "pcm";
        private int sampleRate = 16000;
        private int chunkMs = 200;
        /**
         * Fun-ASR VAD 断句静音阈值（毫秒）；≤0 时用 {@link Asr#sentenceSilenceMs}。
         */
        private int maxSentenceSilenceMs;
        /**
         * Fun-ASR 语音/噪音阈值，范围 [-1.0, 1.0]；null 表示不传该参数。
         * 越接近 +1 越严（更多被当噪声），越接近 -1 越松。
         */
        private Double speechNoiseThreshold = 0.3;
        private int connectTimeoutMs = 5000;
        private int sendTimeoutMs = 10000;
        private int resultTimeoutMs = 15000;

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public int resolveMaxSentenceSilenceMs(Asr asr) {
            if (maxSentenceSilenceMs > 0) {
                return maxSentenceSilenceMs;
            }
            return asr == null ? 600 : asr.resolveSentenceSilenceMs();
        }
    }

    @Data
    public static class DashScopeTts {
        private String workspaceId = "";
        private String apiKey = "";
        private String region = "cn-beijing";
        private String endpoint = "";
        private String model = "cosyvoice-v3-flash";
        private String voice = "longanyang";
        private String format = "pcm";
        private int sampleRate = 16000;
        /** 语速倍数 [0.5, 2.0]。 */
        private double rate = 1.0;
        /** 音量 [0, 100]。 */
        private int volume = 50;
        /** 音调倍数 [0.5, 2.0]。 */
        private double pitch = 1.0;
        private int connectTimeoutMs = 5000;
        private int sendTimeoutMs = 10000;
        private int sessionTimeoutMs = 30000;
        /** flush / 收尾时等待音频的上限（毫秒）。 */
        private int chunkWaitMs = 200;
        /**
         * 非 flush 的 feed 等待音频上限；0=发完即返回（推荐，由后续 feed/flush 再 drain）。
         */
        private int nonFlushChunkWaitMs = 0;

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    @Data
    public static class VolcTts {
        private String endpoint = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";
        private String apiKey = "";
        private String appKey = "";
        private String accessKey = "";
        private String resourceId = "seed-tts-2.0";
        private String speaker = "zh_female_vv_uranus_bigtts";
        private String model = "seed-tts-2.0-standard";
        private int connectTimeoutMs = 5000;
        private int sendTimeoutMs = 10000;
        private int sessionTimeoutMs = 30000;
        /** flush / 收尾时等待音频的上限（毫秒）。 */
        private int chunkWaitMs = 200;
        /**
         * 非 flush 的 feed 等待音频上限；0=发完即返回。
         */
        private int nonFlushChunkWaitMs = 0;

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasLegacyCredentials() {
            return appKey != null && !appKey.isBlank()
                    && accessKey != null && !accessKey.isBlank();
        }
    }
}
