package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "工具权限配置")
public class PermissionsConfigDto implements Serializable {

    @Schema(description = "自动允许的工具规则列表，如 [\"Bash(npm run lint)\", \"Read(~/.zshrc)\"]")
    private List<String> allow;

    @Schema(description = "拒绝的工具规则列表，如 [\"Bash(curl *)\", \"Read(./.env)\"]")
    private List<String> deny;

    @Schema(description = "需要询问的工具规则列表，如 [\"Bash(git push *)\"]")
    private List<String> ask;
}
