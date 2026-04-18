package com.xspaceagi.agent.core.spec.enums;

public enum UsageScenarioEnum {
    PageApp, TaskAgent, ChatBot, Workflow, OpenApi;

    public static UsageScenarioEnum fromString(String value) {
        for (UsageScenarioEnum scenario : UsageScenarioEnum.values()) {
            if (scenario.name().equalsIgnoreCase(value)) {
                return scenario;
            }
        }
        return null;
    }
}
