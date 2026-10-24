package com.xspaceagi.im.application;

/**
 * 按平台用户主动推送到飞书/钉钉/企微。
 */
public interface ImProactivePushApplicationService {

    /**
     * @param channel         feishu/dingtalk/wework
     * @param userId          平台用户 ID
     * @param title           可选标题
     * @param content         消息正文
     * @param channelConfigId 可选，多配置时指定
     */
    void send(String channel, Long userId, String title, String content, Long channelConfigId);
}
