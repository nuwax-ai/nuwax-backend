package com.xspaceagi.system.spec.constants;

/**
 * i18n 内置 JSON 导出目录与命名（与 classpath {@code i18n/} 下资源一致）。
 */
public final class I18nSyncConstants {

    private I18nSyncConstants() {
    }

    /**
     * 导出 i18n JSON 的默认基础路径（相对项目根目录，与权限导出同级约定）。
     */
    public static final String I18N_JSON_EXPORT_BASE_PATH =
            "app-platform-modules/platform-system/system-application/src/main/resources/i18n";

    /**
     * classpath 下语言列表：{@code i18n/i18n-lang-{version}.json}
     */
    public static String buildI18nLangClasspathPath(String version) {
        return "i18n/i18n-lang-" + version + ".json";
    }

    /**
     * 导出文件名：{@code i18n-config-{version}.json}
     */
    public static String buildI18nConfigExportFileName(String version) {
        return "i18n-config-" + version + ".json";
    }

    /**
     * classpath 配置项：{@code i18n/i18n-config-{version}.json}
     */
    public static String buildI18nConfigClasspathPath(String version) {
        return "i18n/" + buildI18nConfigExportFileName(version);
    }

    /**
     * 新增差异文件名：{@code i18n-config-{version}-add.json}
     */
    public static String buildI18nConfigAddFileName(String version) {
        return "i18n-config-" + version + "-add.json";
    }

    /**
     * classpath 新增差异配置：{@code i18n/i18n-config-{version}-add.json}
     */
    public static String buildI18nConfigAddClasspathPath(String version) {
        return "i18n/" + buildI18nConfigAddFileName(version);
    }

    /**
     * 变更差异文件名：{@code i18n-config-{version}-update.json}
     */
    public static String buildI18nConfigUpdateFileName(String version) {
        return "i18n-config-" + version + "-update.json";
    }

    /**
     * classpath 变更差异配置：{@code i18n/i18n-config-{version}-update.json}
     */
    public static String buildI18nConfigUpdateClasspathPath(String version) {
        return "i18n/" + buildI18nConfigUpdateFileName(version);
    }
}
