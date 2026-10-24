package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.system.sdk.server.IUserConfigRpcService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserChatBoxConfigService {

    @Resource
    private IUserConfigRpcService iUserConfigRpcService;

    public ChatBoxConfig getUserAgentChatBoxConfig(Long tenantId, Long userId, Long agentId) {
        ChatBoxConfig chatBoxConfig = new ChatBoxConfig();
        Object userConfig = iUserConfigRpcService.getUserConfig(tenantId, userId, "chatbox.config." + agentId);
        if (userConfig != null && userConfig instanceof Map<?, ?> mapConfig) {
            if (((Map<?, ?>) userConfig).get("mode") != null) {
                chatBoxConfig.setMode(((Map<?, ?>) userConfig).get("mode").toString());
            }
            try {
                if (((Map<?, ?>) userConfig).get("enableVersionControl") != null) {
                   Integer enableVersionControl = Integer.parseInt(((Map<?, ?>) userConfig).get("enableVersionControl").toString());
                   chatBoxConfig.setEnableVersionControl(enableVersionControl);
                }
                if (((Map<?, ?>) userConfig).get("autoCommit") != null) {
                    Integer autoCommit = Integer.parseInt(((Map<?, ?>) userConfig).get("autoCommit").toString());
                    chatBoxConfig.setAutoCommit(autoCommit);
                }
            } catch (NumberFormatException e) {
                //
            }
        }
        return chatBoxConfig;
    }

    @Data
    public static class ChatBoxConfig {
        private String mode;
        private Integer enableVersionControl;
        private Integer autoCommit;
    }
}
