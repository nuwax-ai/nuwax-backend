package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;

public interface IUserAppApplicationService {

    /** 预删除保留期（天）：期满后由统一调度任务物理删除 */
    int PRE_DELETE_RETENTION_DAYS = 7;

    /** 创建全栈应用 */
    UserAppModel create(UserAppModel model, String workspacePath);

    UserAppModel update(UserAppModel model, UserContext userContext);

    /** 删除项目：标记预删除（立即前端不可见、代理404，已发布先停生产），保留期满由调度任务物理删除 */
    void delete(Long id, UserContext userContext);

    UserAppModel getById(Long id);

    /** 补齐资料库资源（详情查询用）：无目录则建 folder，无计划表则在目录下建 datatable，已建直接跳过 */
    UserAppModel ensureRepoResources(Long id);

    List<UserAppModel> list(UserAppModel query);

    SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize);

    /** 记录一次构建版本（build 产物归档时调用；追加版本记录，新版本 latest=true；乐观锁并发安全、同版本幂等） */
    void recordBuildVersion(Long id, String version, String gitCommit, String packageUrl, UserContext userContext);

    /** 更新发布状态 */
    void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext);

    /** 发布上架前置条件校验：项目存在且已部署生产环境（prod_deployed=true 且记录了部署版本），校验通过返回当前部署的版本记录（发布以该版本为准，不要求最新版本）；供各发布入口共用 */
    BuildVersionDto checkPublishPrecondition(Long id);

    /** 保存数据库账号密码：同步调沙箱重置，成功再加密落库；返回下游 message */
    String saveDbCredential(Long id, boolean devEnv, String username, String password, UserContext userContext);

    /** 读取数据库密码明文（解密） */
    String getDbPassword(Long id, boolean devEnv);

    /** 预删除保留期已满、待物理删除的项目（跨租户系统扫描；统一调度任务专用，业务查询勿用） */
    List<UserAppModel> listPreDeleteDue();

    /** 物理删除单个已预删除的项目（不校验保留期，保留期由调用方控制；以项目租户上下文调用） */
    void purge(Long id);

    /**
     * 清理开发工作区目录（Cloud 沙箱）：按用户项目工作区绑定（user_project_workspace）逐用户删除，
     * 不同用户使用不同云端沙箱时各自沙箱上的目录都会清理，全部成功后删除绑定记录；个人沙箱跳过；
     * 返回 false 表示有清理失败（绑定记录保留、行未删可整体重试），供预删除 sweep 与立即删除共用
     */
    boolean cleanupWorkspace(Long appId);
}
