package com.xspaceagi.im.application;

/**
 * IM 渠道智能体输出统一后处理（与企微 {@code ImWeworkController#processAgentOutput} 同源逻辑，委托 {@code ImOutputProcessor}）。
 */
public interface ImAgentOutputProcessService {

    /**
     * @param text             智能体原始输出
     * @param conversationId   会话 ID（沙箱文件列表、会话链接等）
     * @param agentId          智能体 ID
     * @param tenantId         租户
     * @param userId           渠道归属用户（文件分享等）
     * @param imChannelCode    渠道编码（如 {@code wechat_ilink}）；为 {@code null} 时走通用 Markdown 输出
     */
    String processAgentOutput(String text, Long conversationId, Long agentId, Long tenantId, Long userId, String imChannelCode);

    default String processAgentOutput(String text, Long conversationId, Long agentId, Long tenantId, Long userId) {
        return processAgentOutput(text, conversationId, agentId, tenantId, userId, null);
    }
}
