package com.xspaceagi.userapp.domain.service;

import java.util.Date;
import java.util.List;

import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.sdk.dto.UserAppTaskType;

public interface IUserAppTaskDomainService {

    /**
     * 插入任务守卫行（status=pending，占用同守卫组槽位 active_guard）。
     * 同守卫组已有活跃任务抛 3298
     * （dev 组：dev_start/dev_restart 互斥；build 组独立，可与开发容器任务并行）。
     * 下游受理与状态回填由调用方随后执行（markAccepted / markFailed）。
     */
    UserAppTaskModel submitTask(UserAppModel project, UserAppTaskType taskType, Long userId);

    /** 构建任务行回填 git commit 快照（build 受理后异步抓取成功时调用；空值跳过） */
    void recordGitCommit(Long id, String gitCommit);

    /** 下游受理成功 */
    void markAccepted(Long id, String taskId, String downstreamStatus, String sandboxId);

    /** 下游受理失败 */
    void markFailed(Long id, String error);

    void devStop(Long appId, Long userId);

    /**
     * 按任务受理记录解析沙箱目标：属主校验用项目创建者（沙箱绑定在创建者名下，与任务提交者无关），
     * 校验失败回退按应用解析；下游调用的 user_id 仍由调用方用任务提交者（会话用户）传
     */
    UserAppSandboxHandle resolveTaskHandle(Long appId, String sandboxId);

    UserAppTaskModel cancel(String taskId);

    UserAppTaskModel getById(Long id);

    UserAppTaskModel getByTaskId(String taskId);

    UserAppTaskModel getLatestByAppId(Long appId, UserAppTaskType taskType);

    /** 应用当前进行中（pending/running）任务行，按 id 倒序 */
    List<UserAppTaskModel> findActiveByAppId(Long appId);

    /**
     * 轮询一次下游任务状态并落库（调度线程，需已设置租户上下文）。
     * BUILD 任务下游 completed 时返回 buildArtifactReady（行保持 running），
     * 由应用层完成产物归档后调用 {@link #finalizeBuild} 终结。
     */
    UserAppTaskPollOutcome pollOnce(Long rowId, int execTimes, int maxExecTimes);

    /** BUILD 产物归档后的终结：error 为空置 completed，非空置 failed 并记录原因 */
    void finalizeBuild(Long id, String error);

    /** 物理删除应用全部任务行（应用清理路径专用） */
    void deleteByAppId(Long appId);

    /** 系统维护跨租户扫描：创建早于 deadline 仍活跃（pending/running）的行（超时兜底 sweeper 用） */
    List<UserAppTaskModel> findOverdueActive(Date deadline);

    /** 系统维护跨租户扫描：创建早于 deadline 的终态行（保留期清理 sweeper 用） */
    List<UserAppTaskModel> findOverdueTerminal(Date deadline);

    /** 按主键删除单行（保留期清理用，需已设置行所属租户上下文） */
    void deleteById(Long id);
}
