package com.xspaceagi.userapp.sdk;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.UserProjectCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectWorkspaceDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectQueryReq;
import com.xspaceagi.userapp.sdk.dto.UserProjectUpsertDto;

/**
 * 用户项目统一登记 RPC 服务
 */
public interface IUserProjectRpcService {

    /**
     * 登记项目（幂等：按 (projectType, refId) 存在则更新展示字段与空间，不存在则插入）
     */
    UserProjectDto register(UserProjectUpsertDto upsertDto);

    /**
     * 创建常规项目
     */
    UserProjectDto createNormal(UserProjectCreateDto createDto);

    /**
     * 查/建 用户×项目 工作目录绑定（同用户同项目唯一目录，已有绑定直接返回；沙箱按项目绑定值解析，项目不存在报错）：
     * 目录一律按默认规则生成（项目级自定义目录仅在项目/应用创建入口绑定）
     */
    UserProjectWorkspaceDto ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId);

    /**
     * 纯查询当前用户的项目工作目录绑定（不创建）；无绑定返回 null
     */
    UserProjectWorkspaceDto getUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId);

    /**
     * 校验会话级自定义工作目录（不落绑定，只校验）：沙箱须为属于本人的启用个人沙箱，
     * 目录归一化后不得占用其他项目绑定目录（本项目自有目录不算占用）。
     *
     * @return 归一化后的目录（写会话双路径用）
     */
    String validateConversationWorkspace(Long userId, Long projectId, Long sandboxId, String workspacePath);

    /**
     * 同步基本信息（传 null 的字段不更新；未登记时忽略并告警）
     */
    void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon);

    /**
     * 注销登记（源项目删除时调用；未登记时静默）
     */
    void unregister(ProjectTypeEnum projectType, Long refId);

    /**
     * 按ID查询
     */
    UserProjectDto queryById(Long id);

    /**
     * 按统一项目ID+类型定位
     */
    UserProjectDto queryByProjectId(ProjectTypeEnum projectType, Long projectId);

    /**
     * 按 (projectType, refId) 查询
     */
    UserProjectDto queryByRef(ProjectTypeEnum projectType, Long refId);

    /**
     * 分页查询
     */
    SuperPage<UserProjectDto> pageQuery(PageQueryVo<UserProjectQueryReq> pageQueryVo);

    /**
     * 三方应用发布上架前置校验：项目存在且为三方应用、OAuth2 凭证已生成；
     * 提交发布申请/审核通过前共用（市场发布流程调用）
     */
    void checkThirdAppPublishPrecondition(Long projectId);

    /**
     * 三方应用发布状态回写（市场发布流程调用：申请中/已发布/拒绝/下架）；
     * 上架（Published）时项目不存在或非三方应用则报错（中断审核事务防孤儿市场记录），
     * 其余状态此时忽略并告警（不阻断拒绝/下架后的清理）
     */
    void updateThirdAppPublishStatus(Long projectId, UserAppPublishStatus status, UserContext userContext);
}
