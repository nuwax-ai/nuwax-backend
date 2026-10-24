package com.xspaceagi.userapp.domain.model;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

import cn.hutool.core.comparator.VersionComparator;
import com.xspaceagi.userapp.sdk.dto.SandboxTypeEnum;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

import lombok.Data;

@Data
public class UserAppModel {

    private Long id;

    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private String coverImg;

    private Long sandboxId;

    private SandboxTypeEnum sandboxType;

    private UserAppDeployType deployType;

    private Long deployServerId;

    private String repoSlugId;

    private String planSlugId;

    private Long devAgentId;

    private Boolean prodDeployed;

    private Boolean prodRunning;

    private String prodReleaseId;

    private UserAppPublishStatus publishStatus;

    private List<BuildVersionDto> buildVersions;

    private String devDbUsername;

    private String devDbPassword;

    private String prodDbUsername;

    private String prodDbPassword;

    private Date preDeleteTime;

    private Date modified;

    private Date created;

    /** 生产环境是否处于停止状态：部署过（prodDeployed）且当前未运行；从未部署不算停止（代理透传下游，不拦截） */
    public boolean prodStopped() {
        return Boolean.TRUE.equals(prodDeployed) && !Boolean.TRUE.equals(prodRunning);
    }

    // ======================= 构建版本查找（全模块唯一定义） =======================

    /** 按版本号精确查构建版本记录 */
    public BuildVersionDto findBuildVersion(String version) {
        if (buildVersions == null || version == null || version.isBlank()) {
            return null;
        }
        return buildVersions.stream()
                .filter(v -> v != null && version.equals(v.getVersion()))
                .findFirst().orElse(null);
    }

    /** 是否已记录该版本（构建产物归档幂等判断） */
    public boolean hasBuildVersion(String version) {
        return findBuildVersion(version) != null;
    }

    /**
     * 最新构建版本：latest=true 中取版本号最大的一条。
     * 正常归档只会有一条 latest=true；并发归档等异常数据下以版本号最大者为准。
     */
    public BuildVersionDto latestBuildVersion() {
        if (buildVersions == null || buildVersions.isEmpty()) {
            return null;
        }
        return buildVersions.stream()
                .filter(v -> v != null && Boolean.TRUE.equals(v.getLatest()))
                .max(Comparator.comparing(BuildVersionDto::getVersion, UserAppModel::compareVersion))
                .orElse(null);
    }

    /** 版本号比较：委托 hutool VersionComparator（缺失段按0补齐，"2"=="2.0"），null 视为最小 */
    public static int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return v1 == null ? (v2 == null ? 0 : -1) : 1;
        }
        return VersionComparator.INSTANCE.compare(v1, v2);
    }
}
