package com.xspaceagi.repo.spec.enums;

/**
 * 文档页面类型（对应 repo_page.page_type 列）。
 */
public enum RepoPageTypeEnum {

    /**
     * 富文本文档
     */
    DOC("doc"),

    /**
     * 表格（正文以表格为主，编辑器承载为可协作表格）
     */
    SHEET("sheet"),

    /**
     * 多维表格（复用 sheet 的 workbook 结构，前端以「字段/记录」多维表格视图承载）
     */
    DATATABLE("datatable"),

    /**
     * 文件夹（纯容器节点：无正文快照、不进搜索索引，仅用于组织页面树层级）
     */
    FOLDER("folder");

    private final String code;

    RepoPageTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RepoPageTypeEnum fromCode(String code) {
        for (RepoPageTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return DOC;
    }

    public static boolean isValid(String code) {
        for (RepoPageTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
