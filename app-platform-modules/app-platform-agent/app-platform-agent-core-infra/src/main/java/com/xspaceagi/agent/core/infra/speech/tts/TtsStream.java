package com.xspaceagi.agent.core.infra.speech.tts;

import java.util.List;

/**
 * 一次 playback 对应一条 TTS 合成会话。Agent 文本分段 {@link #feed}，provider 返回 PCM16 并按帧下发。
 */
public interface TtsStream extends AutoCloseable {

    /**
     * @param textChunk 增量文本；可为空字符串
     * @param flush     是否为当前 playback 的最后一段文本
     * @return 当前可用的 PCM16 下行帧
     */
    List<byte[]> feed(String textChunk, boolean flush);

    /**
     * 预热底层连接（如 WS + run-task），降低首包 feed 建连时延。
     */
    default void prepare() {
    }

    /**
     * 取出当前已缓冲的 PCM 帧（不发送新文本）；用于非 flush 快回后的空闲补收。
     */
    default List<byte[]> drainReadyFrames() {
        return List.of();
    }

    /**
     * 最多取出 {@code maxFrames} 帧；其余留在 TTS 缓冲，供 outbound 水位封顶。
     */
    default List<byte[]> drainReadyFrames(int maxFrames) {
        return drainReadyFrames();
    }

    /**
     * 取出全部已缓冲帧（含不足一帧的尾部垫齐），用于会话收尾。
     */
    default List<byte[]> drainReadyFramesFully() {
        return drainReadyFrames();
    }

    /**
     * 将未能进入 outbound 的帧退回 TTS PCM 缓冲头部，避免水位封顶时丢音频。
     */
    default void returnReadyFrames(List<byte[]> frames) {
    }

    /** 当前会话内已缓冲、尚未 drain 的 PCM 字节数（用于喂字背压）。 */
    default int pendingPcmBytes() {
        return 0;
    }

    /** 最近一次收到上游 PCM 的 nanoTime；0 表示尚未收到。 */
    default long lastPcmNanos() {
        return 0L;
    }

    /**
     * 上游是否已确认本段合成结束（如百炼 task-finished）。
     * flush 之后若为 false，播报侧须继续拉 PCM，不可按短 quiet 退出。
     */
    default boolean isUpstreamFinished() {
        return true;
    }

    default boolean hasFailed() {
        return false;
    }

    default String lastError() {
        return null;
    }

    /**
     * 连接是否在合成结束前被服务端断开（如百炼 ~23s 空闲断连）。
     * 播报侧据此优先静默重建，不依赖 provider 错误消息关键字匹配。
     */
    default boolean isIdleDisconnected() {
        return false;
    }

    @Override
    void close();
}
