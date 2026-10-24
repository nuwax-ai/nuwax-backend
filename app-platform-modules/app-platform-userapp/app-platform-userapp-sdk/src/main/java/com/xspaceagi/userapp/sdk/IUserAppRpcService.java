package com.xspaceagi.userapp.sdk;

import java.util.List;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.UserAppCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;
import com.xspaceagi.userapp.sdk.dto.UserAppQueryReq;

public interface IUserAppRpcService {

    /**
     * 按ID查询
     */
    UserAppDto queryById(Long id);

    /**
     * 补齐资料库资源（详情查询用）：无目录建 folder、无计划表建 datatable，已建跳过
     */
    UserAppDto ensureRepoResources(Long id);

    /**
     * 条件查询列表
     */
    List<UserAppDto> list(UserAppQueryReq req);

    /**
     * 分页查询
     */
    SuperPage<UserAppDto> pageQuery(PageQueryVo<UserAppQueryReq> pageQueryVo);

    /**
     * 创建项目（每次调用创建新项目）。
     */
    UserAppDto create(UserAppCreateDto createDto);

    /**
     * 更新基本信息（传 null 的字段不更新）
     */
    void updateBasicInfo(Long id, String name, String description, String icon, String coverImg, UserContext userContext);

    /**
     * 删除项目：标记预删除（立即对前端不可见、代理404，已发布先停生产），
     * 保留期满后由统一调度任务物理删除
     */
    void delete(Long id, UserContext userContext);

    /**
     * 保存数据库账号密码：同步调沙箱重置，成功再加密存储
     */
    String saveDbCredential(Long id, boolean devEnv, String username, String password, UserContext userContext);

    /**
     * 读取数据库密码明文（解密）
     */
    String getDbPassword(Long id, boolean devEnv);

    /**
     * 更新发布状态（市场发布流程回写）
     */
    void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext);

    /**
     * 发布上架前置条件校验：项目存在且已部署生产环境（prod_deployed=true 且记录了部署版本），
     * 校验通过返回当前部署的版本记录（发布以该版本为准，不要求最新版本），否则抛异常（各发布入口共用同一规则）
     */
    BuildVersionDto checkPublishPrecondition(Long appId);

    /**
     * 预删除保留期已满、待物理删除的项目（跨租户系统扫描；统一调度任务专用，业务查询勿用）
     */
    List<UserAppDto> listPreDeleteDue();

    /**
     * 物理删除单个已预删除的项目（须以项目租户上下文调用；保留期由调用方控制）
     */
    void purge(Long id);

}
