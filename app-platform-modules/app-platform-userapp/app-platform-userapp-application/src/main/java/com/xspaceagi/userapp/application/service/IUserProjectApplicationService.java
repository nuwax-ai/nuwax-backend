package com.xspaceagi.userapp.application.service;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;

import java.util.List;

public interface IUserProjectApplicationService {

    /**
     * 登记项目（幂等：按 (projectType, refId) 存在则更新展示字段与空间，不存在则插入）。
     */
    UserProjectModel register(ProjectTypeEnum projectType, Long refId, String name, String description,
            String icon, Long spaceId, Long creatorId, Long sandboxId, Long devAgentId, Boolean nameDefined);

    /** 同步基本信息（null 字段不更新；未登记时忽略） */
    void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon);

    /** 注销登记（未登记时静默） */
    void unregister(ProjectTypeEnum projectType, Long refId);

    /** 创建常规项目（type=NormalProject，无引用） */
    /** 创建常规项目（内部完成创建者的工作目录登记：workspacePath 自定义或规则生成） */
    UserProjectModel createNormal(UserProjectModel model, String workspacePath);

    /**
     * 创建三方应用（type=ThirdApp，无引用表，项目 id 即本表主键、refId 恒为空）。
     * <p>
     * 与 {@link #createNormal} 的差别：三方应用不是开发态项目，不分配/校验沙箱，
     * 也不建工作目录与资料库协作目录。凭证由上层按需生成。
     */
    UserProjectModel createThirdApp(UserProjectModel model);

    /**
     * 查/建 用户×项目 工作目录绑定（同用户同项目唯一目录，已有绑定直接返回并忽略传入的沙箱/目录）：
     * 无绑定时——传入 workspacePath（仅个人沙箱）则绑定自定义目录（被占用报错），否则按类型规则生成。
     */
    UserProjectWorkspaceModel ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId, Long sandboxId, String workspacePath);

    /** 同上，沙箱自动按项目绑定值解析（项目不存在报错），目录一律按默认规则——会话创建等无项目行上下文的调用方使用 */
    UserProjectWorkspaceModel ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId);

    /** 纯查询当前用户的项目工作目录绑定（不创建）；无绑定返回 null */
    UserProjectWorkspaceModel getUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId);

    /**
     * 校验会话级自定义工作目录（不落绑定，只校验）：沙箱须存在、启用且个人沙箱属于本人
     * （agent 执行与文件访问同机同路径，恒等），目录归一化后不得占用其他项目的绑定目录
     * （本项目自有绑定不算占用——会话目录=项目目录，等价于显式继承）。
     *
     * @return 归一化后的目录（写会话双路径用）
     */
    String validateConversationWorkspace(Long userId, Long projectId, Long sandboxId, String workspacePath);

    /** 更新常规项目基本信息（仅 NormalProject；null 字段不更新） */
    UserProjectModel updateNormal(Long id, String name, String description, String icon);

    /**
     * 更新三方应用基本信息（仅 ThirdApp）。
     * <p>
     * null 字段不更新；非 null 一律写入（因此传空串可清空描述/图标）。
     * 名称传 null 以外的空值会被拒绝，避免出现无名应用。
     */
    UserProjectModel updateThirdApp(Long id, String name, String description, String icon);

    /** 更新置顶/归档标记（按统一表主键定位，跨类型） */
    UserProjectModel updateFlags(Long id, Boolean pinned, Boolean archived);

    /** 删除常规项目（仅 NormalProject） */
    void deleteNormal(Long id);

    /**
     * 删除三方应用（仅 ThirdApp）。
     * <p>
     * 三方应用不是开发态项目，没有沙箱/工作目录/资料库目录，也没有会话与收藏关联，
     * 删除即摘掉这一行 {@code user_project}（凭证字段随行消失）。已签发的授权码与令牌
     * 无需另行清理：校验链每次都按 client_id 回查此表（{@code OAuth2TokenRpcServiceImpl}），
     * 行不存在即视为客户端不存在，旧令牌立即失效。
     * 市场数据随删清理：发布记录、统计、收藏/最近使用关系与在途审核单。
     */
    void deleteThirdApp(Long id);

    /**
     * 三方应用发布上架前置校验：项目存在且为三方应用、OAuth2 凭证（clientId/secret）已生成。
     * 提交发布申请与审核通过两条入口共用同一规则。
     */
    void checkThirdAppPublishPrecondition(Long id);

    /**
     * 三方应用发布状态回写（市场发布流程：申请中/已发布/拒绝/下架）。
     * 上架（Published）回写时源行已删或非三方应用则抛错中断审核事务（防孤儿市场记录）；
     * 其余状态源行已删时告警忽略，不阻断拒绝/下架后的清理
     */
    void updateThirdAppPublishStatus(Long id, UserAppPublishStatus status);

    UserProjectModel getById(Long id);

    /** 补齐资料库资源（详情查询用）：无目录则建 folder，无计划表则在目录下建 datatable，已建直接跳过 */
    UserProjectModel ensureRepoResources(Long id);

    UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId);

    /** 按类型 + 引用ID集合批量查询（UserApp 等有引用表类型，refId 即业务项目主键） */
    List<UserProjectModel> listByRefs(ProjectTypeEnum projectType, List<Long> refIds);

    List<UserProjectModel> listByIds(List<Long> ids);

    /** 按统一项目ID+类型定位 */
    UserProjectModel queryByProjectId(ProjectTypeEnum projectType, Long projectId);

    SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize);
}
