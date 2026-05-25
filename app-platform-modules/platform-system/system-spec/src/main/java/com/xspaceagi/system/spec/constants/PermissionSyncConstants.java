package com.xspaceagi.system.spec.constants;

/**
 * 权限同步 JSON 文件路径常量
 * <p>
 * 导出与导入使用同一目录：{@code system-application/src/main/resources/permission/}
 * 文件命名：{@code permission-{version}.json}
 * </p>
 */
public class PermissionSyncConstants {

    private PermissionSyncConstants() {
    }

    /**
     * 导入权限 JSON 的 classpath 路径前缀
     * 完整路径：{@code permission/permission-{version}.json}
     */
    public static final String PERMISSION_JSON_CLASSPATH_PREFIX = "permission/permission-";

    /**
     * 导出权限 JSON 的默认基础路径（相对项目根目录）
     * 完整路径：{@code {basePath}/permission-{version}.json}
     */
    public static final String PERMISSION_JSON_EXPORT_BASE_PATH = "app-platform-modules/platform-system/system-application/src/main/resources/permission";

    /**
     * 根据版本构建 classpath 路径（导入权限用）
     */
    public static String buildClasspathPath(String version) {
        return PERMISSION_JSON_CLASSPATH_PREFIX + version + ".json";
    }

    /**
     * 根据版本构建导出文件路径（导出权限用，需拼接 basePath）
     */
    public static String buildExportFileName(String version) {
        return "permission-" + version + ".json";
    }

    /**
     * 差异 JSON 的 classpath 路径前缀
     * 完整路径：{@code permission/permission-{toVersion}-diff.json}
     */
    public static String buildDiffClasspathPath(String toVersion) {
        return "permission/permission-" + toVersion + "-diff.json";
    }

    /**
     * 根据目标版本构建差异文件名
     */
    public static String buildDiffFileName(String toVersion) {
        return "permission-" + toVersion + "-diff.json";
    }
}
