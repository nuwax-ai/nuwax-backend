package com.xspaceagi.agent.core.spec.enums;

public enum ModelCapabilityEnum {
    Text,
    Image,
    Audio,
    Video,
    TextEmbedding,
    MultiEmbedding,
    Reasoning;

    public static ModelCapabilityEnum fromString(String value) {
        for (ModelCapabilityEnum e : ModelCapabilityEnum.values()) {
            if (e.name().equalsIgnoreCase(value)) {
                return e;
            }
        }
        return null;
    }
}
