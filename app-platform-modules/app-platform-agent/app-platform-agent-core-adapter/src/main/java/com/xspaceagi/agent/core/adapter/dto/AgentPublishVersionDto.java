package com.xspaceagi.agent.core.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPublishVersionDto implements Serializable {

    private String version;

    private String gitCommit;

    @Builder.Default
    private Boolean latest = true;

    private List<PackageArtifact> packages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageArtifact implements Serializable {
        private String platform;
        private String url;
    }
}
