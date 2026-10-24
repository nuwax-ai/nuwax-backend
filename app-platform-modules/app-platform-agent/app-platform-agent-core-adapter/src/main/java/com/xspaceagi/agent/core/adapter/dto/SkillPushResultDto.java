package com.xspaceagi.agent.core.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作空间技能推送结果，单个技能发送失败不阻断流程
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillPushResultDto implements Serializable {

    /**
     * 失败技能列表
     */
    @Builder.Default
    private List<FailedSkill> failedSkillList = new ArrayList<>();

    /**
     * 是否存在发送失败的技能
     */
    public boolean hasFailures() {
        return failedSkillList != null && !failedSkillList.isEmpty();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedSkill implements Serializable {

        private String name;

        private String description;

        private String url;

        private String reason;
    }
}
