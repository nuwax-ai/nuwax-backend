package com.xspaceagi.im.web.service;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.im.application.ImAgentOutputProcessService;
import com.xspaceagi.im.web.util.ImOutputProcessor;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 与企微 {@code ImWeworkController} 中站点域名解析 + {@link ImOutputProcessor} 处理链一致。
 */
@Service
public class ImAgentOutputProcessServiceImpl implements ImAgentOutputProcessService {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ImFileShareService imFileShareService;
    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    @Override
    public String processAgentOutput(String text, Long conversationId, Long agentId, Long tenantId, Long userId, String imChannelCode) {
        TenantConfigDto tenantConfig = tenantId != null ? tenantConfigApplicationService.getTenantConfig(tenantId) : null;
        String domain = tenantConfig != null ? tenantConfig.getSiteUrl() : null;
        if (domain != null) {
            domain = domain.trim();
            if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                domain = "https://" + domain;
            }
            if (domain.endsWith("/")) {
                domain = domain.substring(0, domain.length() - 1);
            }
        }
        return ImOutputProcessor.processOutput(text, conversationId, agentId, domain, userId, tenantId,
                imFileShareService, computerFileApplicationService, imChannelCode);
    }
}
