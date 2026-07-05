package com.xspaceagi.agent.core.application.support;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.HookEntryDto;
import com.xspaceagi.agent.core.adapter.dto.HookScriptDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将平台会话变量注入沙箱 agent 环境。
 * <ul>
 *   <li>Claude Code / Codex：SessionStart + UserPromptSubmit hook 写入 {@code CLAUDE_ENV_FILE}</li>
 *   <li>OpenCode：仅写 {@code platform-env.sh}，由 file-server 安装的 platform-env 插件注入（hook 脚本在 OpenCode 下 no-op）</li>
 * </ul>
 */
public final class PlatformEnvHookSupport {

    private static final String ENV_SCRIPT_RELATIVE_PATH = "hooks/platform-env.sh";
    private static final String INJECT_SCRIPT_RELATIVE_PATH = "hooks/inject-platform-env.sh";
    private static final String INJECT_COMMAND = "bash .claude/hooks/inject-platform-env.sh";

    /**
     * 将 platform-env.sh 同步到 CLAUDE_ENV_FILE（Claude/Codex）。
     * OpenCode 下由 opencode-hooks-plugin 传入 OPENCODE_PROJECT_DIR，脚本直接 exit 0。
     */
    private static final String INJECT_SCRIPT_CONTENT = """
            #!/usr/bin/env bash
            set -euo pipefail

            # OpenCode 走 platform-env 插件，不通过 hook 注入
            if [[ -n "${OPENCODE_PROJECT_DIR:-}" ]]; then
              exit 0
            fi

            script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
            env_file="$script_dir/platform-env.sh"
            if [[ ! -f "$env_file" ]]; then
              exit 0
            fi

            if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
              grep '^export ' "$env_file" >"$CLAUDE_ENV_FILE" || true
            fi

            exit 0
            """;

    private PlatformEnvHookSupport() {
    }

    /**
     * 若存在可导出的用户变量，写入 env 脚本并追加 Claude 侧 SessionStart / UserPromptSubmit hook。
     */
    public static void apply(
            Map<String, Object> hookArgs,
            Map<String, List<HookEntryDto>> hooksConfig,
            List<HookScriptDto> hookScripts) {
        if (hookArgs == null || hookArgs.isEmpty() || hooksConfig == null || hookScripts == null) {
            return;
        }

        String envScriptContent = buildPlatformEnvScript(hookArgs);
        if (envScriptContent == null) {
            return;
        }

        hookScripts.add(HookScriptDto.builder()
                .path(ENV_SCRIPT_RELATIVE_PATH)
                .content(envScriptContent)
                .build());
        hookScripts.add(HookScriptDto.builder()
                .path(INJECT_SCRIPT_RELATIVE_PATH)
                .content(INJECT_SCRIPT_CONTENT)
                .build());

        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "command");
        handler.put("command", INJECT_COMMAND);
        handler.put("timeout", 10);

        // SessionStart：会话开始时写入 CLAUDE_ENV_FILE（官方支持）
        HookEntryDto sessionStartEntry = HookEntryDto.builder()
                .hooks(List.of(handler))
                .build();
        hooksConfig.computeIfAbsent("SessionStart", key -> new ArrayList<>()).add(0, sessionStartEntry);

        // UserPromptSubmit：每轮用户消息前刷新变量（SessionStart 之后变量变更时同步）
        HookEntryDto userPromptEntry = HookEntryDto.builder()
                .hooks(List.of(new LinkedHashMap<>(handler)))
                .build();
        hooksConfig.computeIfAbsent("UserPromptSubmit", key -> new ArrayList<>()).add(0, userPromptEntry);
    }

    static String buildPlatformEnvScript(Map<String, Object> hookArgs) {
        if (hookArgs == null || hookArgs.isEmpty()) {
            return null;
        }

        StringBuilder script = new StringBuilder();
        script.append("#!/usr/bin/env bash\n");
        script.append("# Platform-managed session variables. Auto-generated; do not edit.\n");

        boolean hasExport = false;
        for (Map.Entry<String, Object> entry : hookArgs.entrySet()) {
            String exportLine = toExportLine(entry.getKey(), entry.getValue());
            if (exportLine != null) {
                script.append(exportLine).append('\n');
                hasExport = true;
            }
        }

        return hasExport ? script.toString() : null;
    }

    private static String toExportLine(String key, Object value) {
        if (key == null || isEmptyEnvValue(value)) {
            return null;
        }
        String stringValue = toEnvValue(value);
        return "export " + shellSingleQuote(key) + "=" + shellSingleQuote(stringValue);
    }

    private static boolean isEmptyEnvValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String str) {
            return str.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private static String toEnvValue(Object value) {
        if (value instanceof String str) {
            return str;
        }
        return JSON.toJSONString(value);
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
