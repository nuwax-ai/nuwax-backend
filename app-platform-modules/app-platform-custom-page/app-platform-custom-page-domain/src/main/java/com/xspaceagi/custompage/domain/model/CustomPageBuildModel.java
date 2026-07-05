package com.xspaceagi.custompage.domain.model;

import java.util.Date;
import java.util.List;

import com.xspaceagi.custompage.sdk.dto.VersionInfoDto;

import lombok.Data;

@Data
public class CustomPageBuildModel {

    // 项目ID
    private Long id;

    private Long projectId;

    // 1:运行中
    private Integer devRunning;

    private Integer devPid;

    private Integer devPort;

    // 最后保活时间
    private Date lastKeepAliveTime;

    // 1:运行中
    private Integer buildRunning;

    private Date buildTime;

    private Integer buildVersion;

    private Integer codeVersion;

    private List<VersionInfoDto> versionInfo;

    // 上次对话模型ID
    private Long lastChatModelId;

    // 上次多模态ID
    private Long lastMultiModelId;

    private Long tenantId;

    private Long spaceId;

    private Date created;

    private Long creatorId;

    private String creatorName;

    private Date modified;

    private Long modifiedId;

    private String modifiedName;

    // 1:有效; -1:无效
    private Integer yn;

}
