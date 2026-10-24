package com.xspaceagi.userapp.domain.repository;

import java.util.Date;
import java.util.List;

import com.xspaceagi.userapp.domain.model.UserAppTaskModel;

public interface IUserAppTaskRepository {

    UserAppTaskModel getById(Long id);

    UserAppTaskModel getByTaskId(String taskId);

    /** 守卫组当前活跃行（active_guard 唯一键，至多一条；无活跃任务返回 null） */
    UserAppTaskModel findActiveByGuardKey(String guardKey);

    UserAppTaskModel findLatestByAppId(Long appId, String taskType);

    /** 应用当前进行中（pending/running）任务行，按 id 倒序 */
    List<UserAppTaskModel> findActiveByAppId(Long appId);

    Long add(UserAppTaskModel model);

    /**
     * 受理成功回填：下游任务ID + 下游状态 + 沙箱服务器ID（status 为空时不更新状态）。
     *
     * @return true 表示回填生效；false 表示行已被置终态（并发取消/兜底超时），回填未写入
     */
    boolean updateAfterAccepted(Long id, String taskId, String status, String sandboxId);

    /** 构建任务行回填 git commit 快照（不影响任务状态） */
    void updateGitCommit(Long id, String gitCommit);

    /** 非终态下的状态/错误刷新（轮询用） */
    void updateStatusAndError(Long id, String status, String error);

    /**
     * CAS 迁移到终态：SET status/error 且释放活跃槽位（active_guard=NULL），
     * 仅当当前状态仍为 pending/running。
     *
     * @return true 表示本次迁移生效；false 表示已被其它线程迁移到终态
     */
    boolean casToTerminal(Long id, String status, String error);

    /** 按应用删除全部任务行（应用物理删除清理路径） */
    void deleteByAppId(Long appId);

    /** 系统维护跨租户扫描：创建早于 deadline 仍活跃（pending/running）的行，超时兜底用 */
    List<UserAppTaskModel> findActiveOverdue(Date deadline);

    /** 系统维护跨租户扫描：创建早于 deadline 的终态行，保留期清理用 */
    List<UserAppTaskModel> findTerminalOverdue(Date deadline);

    /** 按主键删除单行（保留期清理用） */
    void deleteById(Long id);
}
