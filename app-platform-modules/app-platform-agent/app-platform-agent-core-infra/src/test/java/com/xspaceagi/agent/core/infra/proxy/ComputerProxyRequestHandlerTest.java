package com.xspaceagi.agent.core.infra.proxy;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.xspaceagi.agent.core.infra.proxy.ComputerProxyRequestHandler.resolveTerminalCwd;
import static com.xspaceagi.agent.core.infra.proxy.ComputerProxyRequestHandler.supplementTerminalCwd;
import static com.xspaceagi.agent.core.infra.proxy.ComputerProxyRequestHandler.supplementTerminalServiceType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 终端契约 query 兜底补参纯函数单测（supplementTerminalCwd / resolveTerminalCwd，
 * 及此前缺测的 supplementTerminalServiceType 回填）。
 */
class ComputerProxyRequestHandlerTest {

    // ── supplementTerminalCwd ──────────────────────────────────────────────

    @Test
    @DisplayName("cwd 值为空：原样返回不注入")
    void supplementTerminalCwd_blankValue() {
        assertNull(supplementTerminalCwd(null, null));
        assertNull(supplementTerminalCwd(null, ""));
        assertNull(supplementTerminalCwd(null, "   "));
        assertEquals("a=1", supplementTerminalCwd("a=1", null));
    }

    @Test
    @DisplayName("query 为空且有 cwd 值：注入唯一参数")
    void supplementTerminalCwd_emptyQuery() {
        assertEquals("cwd=%2FUsers%2Ffengfei%2Fagent-platform",
                supplementTerminalCwd(null, "/Users/fengfei/agent-platform"));
        assertEquals("cwd=%2FUsers%2Ffengfei%2Fagent-platform",
                supplementTerminalCwd("", "/Users/fengfei/agent-platform"));
    }

    @Test
    @DisplayName("已显式携带 cwd：原样返回不覆盖（last-wins 保护，键后无值也算已携带）")
    void supplementTerminalCwd_respectsExplicit() {
        String raw = "service_type=computer-normal-project&cwd=%2Fexplicit%2Fdir";
        assertEquals(raw, supplementTerminalCwd(raw, "/Users/fengfei/agent-platform"));
        assertEquals("cwd", supplementTerminalCwd("cwd", "/x"));
    }

    @Test
    @DisplayName("存量 query 以 & 追加 cwd")
    void supplementTerminalCwd_append() {
        assertEquals("service_type=computer-normal-project&cwd=%2FUsers%2Fagent",
                supplementTerminalCwd("service_type=computer-normal-project", "/Users/agent"));
    }

    @Test
    @DisplayName("路径含空格：form 语义编码为 +")
    void supplementTerminalCwd_spaceFormEncoded() {
        assertEquals("cwd=%2FUsers%2Ffengfei%2FMy+Project",
                supplementTerminalCwd(null, "/Users/fengfei/My Project"));
    }

    @Test
    @DisplayName("路径含中文 / 字面 + ：UTF-8 percent 与 %2B 编码")
    void supplementTerminalCwd_unicodeAndPlus() {
        assertEquals("cwd=%2FUsers%2Ffengfei%2F%E5%A5%B3%E5%A8%B2%E9%A1%B9%E7%9B%AE",
                supplementTerminalCwd(null, "/Users/fengfei/女娲项目"));
        assertEquals("cwd=%2Fa%2Bb", supplementTerminalCwd(null, "/a+b"));
    }

    // ── resolveTerminalCwd ─────────────────────────────────────────────────

    private SandboxServerConfig.SandboxServer server(
            SandboxScopeEnum scope, String agentWorkspacePath) {
        SandboxServerConfig.SandboxServer server = new SandboxServerConfig.SandboxServer();
        server.setScope(scope);
        if (agentWorkspacePath != null) {
            ConversationDto conversation = new ConversationDto();
            conversation.setAgentWorkspacePath(agentWorkspacePath);
            server.setCurrentConversation(conversation);
        }
        return server;
    }

    @Test
    @DisplayName("个人电脑（USER scope）+ 自选目录：返回目录值")
    void resolveTerminalCwd_userScope() {
        assertEquals("/Users/fengfei/agent-platform",
                resolveTerminalCwd(server(SandboxScopeEnum.USER, "/Users/fengfei/agent-platform"), false));
    }

    @Test
    @DisplayName("Windows 盘符与 UNC 形态：可注入（下游归一接受）")
    void resolveTerminalCwd_windowsForms() {
        assertEquals("C:\\Users\\fengfei\\proj",
                resolveTerminalCwd(server(SandboxScopeEnum.USER, "C:\\Users\\fengfei\\proj"), false));
        assertEquals("D:/dev/proj",
                resolveTerminalCwd(server(SandboxScopeEnum.USER, "D:/dev/proj"), false));
        assertEquals("\\\\server\\share\\proj",
                resolveTerminalCwd(server(SandboxScopeEnum.USER, "\\\\server\\share\\proj"), false));
    }

    @Test
    @DisplayName("非绝对路径脏数据：不注入（下游对形态非法 cwd 直接 400，兜底不得制造硬失败）")
    void resolveTerminalCwd_relativeRejected() {
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.USER, "relative/path"), false));
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.USER, "home/user/x"), false));
    }

    @Test
    @DisplayName("云端沙箱（非 USER scope）：不注入，本机绝对路径不得泄入容器")
    void resolveTerminalCwd_cloudScope() {
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.GLOBAL, "/Users/fengfei/agent-platform"), false));
    }

    @Test
    @DisplayName("pageApp 轨道 / scope 缺省 / 无会话 / 目录空白 / server 为 null：均不注入")
    void resolveTerminalCwd_negative() {
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.USER, "/x"), true));
        assertNull(resolveTerminalCwd(server(null, "/x"), false));
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.USER, null), false));
        assertNull(resolveTerminalCwd(server(SandboxScopeEnum.USER, "   "), false));
        assertNull(resolveTerminalCwd(null, false));
    }

    // ── supplementTerminalServiceType（既有行为回填） ───────────────────────

    @Test
    @DisplayName("service_type 兜底：非常规项目不动")
    void supplementTerminalServiceType_notNormalProject() {
        assertNull(supplementTerminalServiceType(null, false));
        assertEquals("a=1", supplementTerminalServiceType("a=1", false));
    }

    @Test
    @DisplayName("service_type 兜底：常规项目空 query 补 computer-normal-project")
    void supplementTerminalServiceType_blankQuery() {
        assertEquals("service_type=computer-normal-project", supplementTerminalServiceType(null, true));
    }

    @Test
    @DisplayName("service_type 兜底：已携带不覆盖、缺省以 & 追加")
    void supplementTerminalServiceType_existing() {
        assertEquals("service_type=x", supplementTerminalServiceType("service_type=x", true));
        assertEquals("a=1&service_type=computer-normal-project", supplementTerminalServiceType("a=1", true));
    }
}
