package com.xspaceagi.agent.core.infra.rpc.dto;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

@Component
@Data
public class SandboxServerConfig implements Serializable {

    private List<SandboxServer> sandboxServers;
    private double perUserMemoryGB;
    private int perUserCpuCores;

    @Data
    public static class SandboxServer {
        private String serverId;
        private String serverName;
        private String serverAgentUrl;
        private String serverVncUrl;
        private String serverFileUrl;
        private String serverApiKey;
        private String configKey;
        private int maxUsers;
        private double perUserMemoryGB;
        private int perUserCpuCores;
        private ConversationDto currentConversation;
        private SandboxScopeEnum scope;
    }
}
