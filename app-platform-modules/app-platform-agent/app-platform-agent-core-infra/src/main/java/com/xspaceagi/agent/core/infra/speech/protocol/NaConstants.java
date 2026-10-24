package com.xspaceagi.agent.core.infra.speech.protocol;

public final class NaConstants {

    public static final byte[] MAGIC = new byte[] { 0x4E, 0x41 };
    public static final int HEADER_BYTES = 22;
    public static final int VERSION = 1;

    public static final int CODEC_PCM16 = 1;
    public static final int CODEC_OPUS = 2;

    public static final int FLAG_START = 0x01;
    public static final int FLAG_END = 0x02;
    public static final int FLAG_VAD_SPEECH = 0x04;

    public static final int MAX_WEBSOCKET_PAYLOAD_BYTES = 16 * 1024;

    private NaConstants() {
    }
}
