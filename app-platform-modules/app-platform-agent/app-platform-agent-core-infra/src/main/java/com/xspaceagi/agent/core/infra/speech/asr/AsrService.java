package com.xspaceagi.agent.core.infra.speech.asr;

import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;

public interface AsrService {

    void feedFrame(NaFrame frame);

    AsrResult flush();

    /** listen 开始前预建立 ASR 连接，避免首帧上传时再握手。 */
    default void prepare() {
    }

    /** 在 flush 前放弃 listen 会话时，释放 ASR 提供商占用的资源。 */
    default void close() {
    }

    /** 注册流式回调；App 听音用定稿/partial 推字幕，可忽略。 */
    default void setListener(AsrListener listener) {
    }

    /**
     * 连接是否在识别结束前被服务端断开（如百炼 ~23s 空闲断连）。
     * 听音侧据此优先静默重建，不依赖 provider 错误消息关键字匹配。
     */
    default boolean isIdleDisconnected() {
        return false;
    }
}
