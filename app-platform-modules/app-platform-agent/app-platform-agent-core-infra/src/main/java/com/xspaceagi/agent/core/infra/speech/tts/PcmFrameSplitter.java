package com.xspaceagi.agent.core.infra.speech.tts;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PcmFrameSplitter {

    private final int frameBytes;

    public static PcmFrameSplitter forDownlink(AudioParams audioParams) {
        audioParams.requireValid();
        return new PcmFrameSplitter(audioParams.downlinkPcm16FrameBytes());
    }

    public PcmFrameSplitter(int frameBytes) {
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("frameBytes must be positive");
        }
        this.frameBytes = frameBytes;
    }

    public int getFrameBytes() {
        return frameBytes;
    }

    public List<byte[]> drain(ByteArrayOutputStream pcmBuffer) {
        return drain(pcmBuffer, false, Integer.MAX_VALUE);
    }

    public List<byte[]> drain(ByteArrayOutputStream pcmBuffer, boolean flushRemainder) {
        return drain(pcmBuffer, flushRemainder, Integer.MAX_VALUE);
    }

    /**
     * @param maxFrames 最多切出的完整帧数；剩余 PCM（含未凑满一帧的尾部）写回 buffer
     */
    public List<byte[]> drain(ByteArrayOutputStream pcmBuffer, boolean flushRemainder, int maxFrames) {
        byte[] all = pcmBuffer.toByteArray();
        if (all.length == 0) {
            return List.of();
        }
        int limit = Math.max(0, maxFrames);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset + frameBytes <= all.length && frames.size() < limit) {
            frames.add(Arrays.copyOfRange(all, offset, offset + frameBytes));
            offset += frameBytes;
        }
        pcmBuffer.reset();
        if (offset < all.length) {
            int remain = all.length - offset;
            if (flushRemainder && remain < frameBytes && frames.size() < limit) {
                byte[] padded = new byte[frameBytes];
                System.arraycopy(all, offset, padded, 0, remain);
                frames.add(padded);
            } else {
                pcmBuffer.write(all, offset, remain);
            }
        }
        return frames;
    }
}
