package com.xspaceagi.userapp.domain.service;

import java.util.Date;
import java.util.List;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;

public interface IUserAppDomainService {

    /**
     * 创建项目记录（每次调用创建新项目）。
     */
    UserAppModel create(UserAppModel model);

    /** 更新（null 字段不更新） */
    UserAppModel update(UserAppModel model, UserContext userContext);

    /** 物理删除 */
    void delete(Long id, UserContext userContext);

    UserAppModel getById(Long id);

    long countByCreator(Long creatorId);

    List<UserAppModel> list(UserAppModel query);

    SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize);

    /** 记录一次构建版本（build 产物归档时调用）：追加版本记录（旧版本 latest=false、新版本 latest=true）；乐观锁并发安全、同版本幂等；不改动发布状态 */
    void recordBuildVersion(Long id, String version, String gitCommit, String packageUrl, UserContext userContext);

    /** 更新发布状态 */
    void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext);

    /** 保存数据库账号密码（密码需已加密） */
    void saveDbCredential(Long id, boolean devEnv, String username, String encryptedPassword, UserContext userContext);

    /** 置生产部署标记并记录部署版本，同时置运行中（幂等；发布上架前置条件，发布以该版本为准；生产停止不清除部署标记——部署态与运行态解耦） */
    void markProdDeployed(Long id, String releaseId);

    /** 置生产已停止（仅运行标记） */
    void markProdStopped(Long id);

    /** 更新发布部署目标（platform 时写云端占位 -1） */
    void updateDeployTarget(Long id, UserAppDeployType deployType, Long deployServerId);

    /** 标记预删除（前端不可见、代理404；物理删除由调度任务延后执行） */
    void markPreDelete(Long id, Date preDeleteTime);

    /** 预删除时间已到期（<= deadline）的项目（跨租户系统扫描，业务查询勿用） */
    List<UserAppModel> listPreDeleteDue(Date deadline);
}
