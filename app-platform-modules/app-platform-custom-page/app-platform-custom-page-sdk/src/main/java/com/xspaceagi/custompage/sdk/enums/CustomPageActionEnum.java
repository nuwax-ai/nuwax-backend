package com.xspaceagi.custompage.sdk.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CustomPageActionEnum {

    CREATE_PROJECT("create_project", "Create project"),
    UPLOAD("upload", "Upload project"),
    SUBMIT_FILES_UPDATE("submit_files_update", "Submit file update"),
    UPLOAD_SINGLE_FILE("upload_single_file", "Upload single file"),
    CHAT("chat", "Chat"),
    ROLLBACK_VERSION("rollback_version", "Rollback version");

    private String code;
    private String name;

}