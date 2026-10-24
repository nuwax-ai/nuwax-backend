package com.xspaceagi.agent.core.infra.speech.asr;

import com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.protocol.NaConstants;
import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;

@Slf4j
public class PcmFrameAccumulator {

    private final AudioParams audioParams;
    private final OpusDecoder opusDecoder;
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();

    public PcmFrameAccumulator(AudioParams audioParams, OpusDecoder opusDecoder) {
        this.audioParams = audioParams;
        this.opusDecoder = opusDecoder;
    }

    public void append(NaFrame frame) {
        if (frame.codec() == NaConstants.CODEC_PCM16) {
            writePcm(frame.payload());
            return;
        }
        if (frame.codec() == NaConstants.CODEC_OPUS) {
            writePcm(opusDecoder.decodePacket(frame.payload(), audioParams));
            return;
        }
        log.warn("Unsupported uplink NA codec {} for hello codec={}", frame.codec(), audioParams.getCodec());
    }

    public int size() {
        return pcmBuffer.size();
    }

    public byte[] toByteArray() {
        return pcmBuffer.toByteArray();
    }

    public void reset() {
        pcmBuffer.reset();
    }

    public int drainToChunkSize(int chunkBytes, ChunkSink sink) throws Exception {
        int sent = 0;
        while (pcmBuffer.size() >= chunkBytes) {
            sink.emit(takeBytes(chunkBytes), false);
            sent++;
        }
        return sent;
    }

    public byte[] takeBytes(int count) {
        if (count <= 0 || pcmBuffer.size() < count) {
            throw new IllegalArgumentException("not enough PCM bytes");
        }
        byte[] all = pcmBuffer.toByteArray();
        byte[] taken = new byte[count];
        System.arraycopy(all, 0, taken, 0, count);
        pcmBuffer.reset();
        if (all.length > count) {
            pcmBuffer.write(all, count, all.length - count);
        }
        return taken;
    }

    public byte[] takeAll() {
        byte[] all = pcmBuffer.toByteArray();
        pcmBuffer.reset();
        return all;
    }

    private void writePcm(byte[] pcm) {
        if (pcm != null && pcm.length > 0) {
            pcmBuffer.writeBytes(pcm);
        }
    }

    @FunctionalInterface
    public interface ChunkSink {
        void emit(byte[] pcm, boolean last) throws Exception;
    }
}
