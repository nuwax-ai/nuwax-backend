package com.xspaceagi.im.application.wechat;

import io.github.kasukusakura.silkcodec.SilkCoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 将微信入站 SILK 语音尽量转为 WAV，便于后续平台统一消费。
 */
public final class WechatIlinkSilkTranscoder {

    private static final int DEFAULT_SAMPLE_RATE = 24_000;

    private WechatIlinkSilkTranscoder() {
    }

    public static byte[] tryConvertSilkToWav(byte[] silkBytes) {
        if (silkBytes == null || silkBytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream silkIn = new ByteArrayInputStream(silkBytes);
             ByteArrayOutputStream pcmOut = new ByteArrayOutputStream()) {
            SilkCoder.decode(silkIn, pcmOut);
            byte[] pcmBytes = pcmOut.toByteArray();
            if (pcmBytes.length == 0) {
                return null;
            }
            return pcmToWav(pcmBytes, DEFAULT_SAMPLE_RATE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static byte[] pcmToWav(byte[] pcmBytes, int sampleRate) throws IOException {
        int totalSize = 44 + pcmBytes.length;
        try (ByteArrayOutputStream wavOut = new ByteArrayOutputStream(totalSize)) {
            writeAscii(wavOut, "RIFF");
            writeIntLE(wavOut, totalSize - 8);
            writeAscii(wavOut, "WAVE");
            writeAscii(wavOut, "fmt ");
            writeIntLE(wavOut, 16);
            writeShortLE(wavOut, 1);
            writeShortLE(wavOut, 1);
            writeIntLE(wavOut, sampleRate);
            writeIntLE(wavOut, sampleRate * 2);
            writeShortLE(wavOut, 2);
            writeShortLE(wavOut, 16);
            writeAscii(wavOut, "data");
            writeIntLE(wavOut, pcmBytes.length);
            wavOut.write(pcmBytes);
            return wavOut.toByteArray();
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }
}
