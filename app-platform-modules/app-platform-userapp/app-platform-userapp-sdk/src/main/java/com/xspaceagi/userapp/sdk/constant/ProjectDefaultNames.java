package com.xspaceagi.userapp.sdk.constant;

import java.util.List;

/**
 * 项目默认名清单
 */
public final class ProjectDefaultNames {

    public static final List<String> DEFAULTS = List.of(
            "未命名项目", "Unnamed project",
            "未命名应用", "Unnamed app");

    public static boolean isDefault(String name) {
        return name != null && DEFAULTS.contains(name.trim());
    }

    private ProjectDefaultNames() {
    }
}
