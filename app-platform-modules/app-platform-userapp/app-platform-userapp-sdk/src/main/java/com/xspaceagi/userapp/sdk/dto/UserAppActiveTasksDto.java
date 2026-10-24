package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 应用进行中任务与操作可用性（前端按 available 标志控制按钮态）
 */
@Data
public class UserAppActiveTasksDto implements Serializable {

    /** 进行中任务，按 id 倒序 */
    private List<UserAppTaskDto> tasks;

    /** 可否发起 dev/start、dev/restart */
    private boolean devActionAllowed;

    /** 可否发起 build */
    private boolean buildAllowed;
}
