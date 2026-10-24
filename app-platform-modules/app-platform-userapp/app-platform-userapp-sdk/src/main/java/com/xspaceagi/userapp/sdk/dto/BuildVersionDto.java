package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.Date;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 构建版本记录。
 * 每次构建产物归档追加一条，所有旧版本 latest=false，新版本 latest=true；
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuildVersionDto implements Serializable {

    private String version;

    private String gitCommit;

    @Builder.Default
    private Boolean latest = true;

    private String packageUrl;

    private Date buildTime;

    /** 校验版本记录完整；部署与发布入口共用 */
    public void requireComplete() {
        if (version == null || version.isBlank() || packageUrl == null || packageUrl.isBlank()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappBuildVersionNotFound);
        }
    }
}
