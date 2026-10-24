package com.xspaceagi.agent.core.infra.speech.tts;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 将 provider 返回的 PCM16 音频缓冲并按下行帧参数切帧。
 * <p>
 * {@link #appendPcm} 不得阻塞（常在 WebSocket/Netty 读回调中调用），否则会卡住上游读循环。
 * 播报线程按 outbound 水位限量 {@link #drainReadyFrames(int)}；喂字背压由
 * {@code pending-pcm-max-buffered-ms} 控制。
 */
public abstract class AbstractPcmTtsStream implements TtsStream {

    private final AudioParams audioParams;
    private final PcmFrameSplitter frameSplitter;
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private final Object pcmLock = new Object();
    private boolean pcmClosed;
    /** 最近一次写入上游 PCM 的时刻；finish 等待可按此判断是否仍有进展。 */
    private volatile long lastPcmNanos;

    protected AbstractPcmTtsStream(AudioParams audioParams) {
        audioParams.requireValid();
        this.audioParams = audioParams;
        this.frameSplitter = PcmFrameSplitter.forDownlink(audioParams);
    }

    @Override
    public int pendingPcmBytes() {
        synchronized (pcmLock) {
            return pcmBuffer.size();
        }
    }

    @Override
    public long lastPcmNanos() {
        return lastPcmNanos;
    }

    /**
     * 按缓冲时长上限换算最大待下发 PCM 字节数（供播报侧水位判断）。
     */
    public static int maxPendingBytesFor(AudioParams audioParams, int maxBufferedMs) {
        audioParams.requireValid();
        int frameMs = audioParams.frameDurationMs();
        int frameBytes = audioParams.downlinkPcm16FrameBytes();
        int ms = Math.max(frameMs, maxBufferedMs);
        int frames = (ms + frameMs - 1) / frameMs;
        return Math.max(frameBytes, frames * frameBytes);
    }

    protected AudioParams audioParams() {
        return audioParams;
    }

    /**
     * 写入上游 PCM。不得阻塞调用线程（尤其是 WS 读回调）。
     */
    protected void appendPcm(byte[] pcm) throws IOException {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        synchronized (pcmLock) {
            if (pcmClosed) {
                return;
            }
            pcmBuffer.write(pcm);
            lastPcmNanos = System.nanoTime();
        }
    }

    protected List<byte[]> drainPcmFrames(boolean flushRemainder) {
        return drainPcmFrames(flushRemainder, Integer.MAX_VALUE);
    }

    protected List<byte[]> drainPcmFrames(boolean flushRemainder, int maxFrames) {
        synchronized (pcmLock) {
            return frameSplitter.drain(pcmBuffer, flushRemainder, maxFrames);
        }
    }

    protected final void markPcmClosed() {
        synchronized (pcmLock) {
            pcmClosed = true;
        }
    }

    @Override
    public List<byte[]> drainReadyFrames() {
        return drainPcmFrames(false);
    }

    @Override
    public List<byte[]> drainReadyFrames(int maxFrames) {
        if (maxFrames <= 0) {
            return List.of();
        }
        return drainPcmFrames(false, maxFrames);
    }

    @Override
    public List<byte[]> drainReadyFramesFully() {
        return drainPcmFrames(true);
    }

    @Override
    public void returnReadyFrames(List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        synchronized (pcmLock) {
            if (pcmClosed) {
                return;
            }
            byte[] existing = pcmBuffer.toByteArray();
            pcmBuffer.reset();
            for (byte[] frame : frames) {
                if (frame != null && frame.length > 0) {
                    pcmBuffer.write(frame, 0, frame.length);
                }
            }
            if (existing.length > 0) {
                pcmBuffer.write(existing, 0, existing.length);
            }
        }
    }
}
