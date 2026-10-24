package com.xspaceagi.repo.spec.enums;

/**
 * 文档版本快照产生原因（对应 repo_page_version.reason 列）。
 */
public enum RepoVersionReasonEnum {

    /**
     * Sidecar 定时自动保存
     */
    AUTOSAVE("autosave"),

    /**
     * 用户手动保存/命名版本
     */
    MANUAL("manual"),

    /**
     * 从历史版本恢复时产生的新快照
     */
    RESTORE("restore");

    private final String code;

    RepoVersionReasonEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RepoVersionReasonEnum fromCode(String code) {
        for (RepoVersionReasonEnum reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        return AUTOSAVE;
    }
}
