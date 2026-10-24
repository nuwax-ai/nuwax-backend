package com.xspaceagi.userapp.application.service.impl;

import cn.hutool.core.util.StrUtil;
import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.repo.sdk.IRepoPageRpcService;
import com.xspaceagi.repo.sdk.dto.RepoPageCreateRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetBySlugRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcDto;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.service.IThirdAppOAuth2ApplicationService;
import com.xspaceagi.userapp.application.service.IUserAppApplicationService;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.application.util.RepoResourceTitleSupport;
import com.xspaceagi.userapp.application.util.UserAppCredentialCipher;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.gateway.UserAppDeployCommand;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.domain.service.*;
import com.xspaceagi.userapp.sdk.constant.ProjectDefaultNames;
import com.xspaceagi.userapp.sdk.dto.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserAppApplicationServiceImpl implements IUserAppApplicationService {

    @Resource
    private IUserAppDomainService userAppDomainService;
    @Resource
    private IUserProjectDomainService userProjectDomainService;
    @Resource
    private IOAuth2ScopeApplyDomainService oauth2ScopeApplyDomainService;
    @Resource
    private IUserProjectWorkspaceDomainService userProjectWorkspaceDomainService;
    @Resource
    private UserAppCredentialCipher userAppCredentialCipher;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;
    @Resource
    private IUserAppSandboxClient userAppSandboxClient;
    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;
    @Resource
    private IUserAppDomainDomainService userAppDomainDomainService;
    @Resource
    private IRepoPageRpcService iRepoPageRpcService;
    @Resource
    private IUserProjectApplicationService userProjectApplicationService;
    @Resource
    private IUserAppTaskDomainService userAppTaskDomainService;
    @Resource
    private UserAppEndpointResolver userAppEndpointResolver;
    @Resource
    private IThirdAppOAuth2ApplicationService thirdAppOAuth2ApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private RepoResourceTitleSupport repoResourceTitleSupport;

    /** 删除模式：immediate=立即彻底删除（含会话/收藏等关联数据）；其余值走预删除一周后物理删除 */
    private static final String DELETE_MODE_IMMEDIATE = "immediate";

    //@Value("${userapp.delete.mode:pre-delete}")
    private final String deleteMode = "pre";

    private static final long CLOUD_SANDBOX_ID = -1L;

    /** 数据库账号/密码初始默认值 */
    private static final String DEFAULT_DB_CREDENTIAL = "dev";

    private static final String DEFAULT_APP_EN_NAME = "Unnamed project";

    private static final String DEFAULT_APP_CN_NAME = "未命名项目";

    @Value("${userapp.domain.dev-suffix:-dev-inner.nuwao.com}")
    private String devDomainSuffix;

    @Value("${userapp.domain.prod-suffix:-prod-inner.nuwao.com}")
    private String prodDomainSuffix;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAppModel create(UserAppModel model, String workspacePath) {
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 名称定义标记：显式命名且非默认名才 true（防中间层把兜底名当显式名漏下来）
        boolean nameDefined = !StrUtil.isBlank(model.getName()) && !ProjectDefaultNames.isDefault(model.getName());
        if (StrUtil.isBlank(model.getName())) {
            model.setName("en-US".equalsIgnoreCase(RequestContext.get().getLang()) ? DEFAULT_APP_EN_NAME : DEFAULT_APP_CN_NAME);
        }
        if (model.getSpaceId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSpaceIdRequired);
        }
        if (model.getCreatorId() == null) {
            throw new IllegalArgumentException("creatorId is required");
        }
        // 数量配额：不限（null/-1）时跳过计数查询，超限直接拒绝（口径=列表同款"预删除不可见"规则）
        UserDataPermissionDto dataPermission = userDataPermissionRpcService.getUserDataPermission(model.getCreatorId());
        Integer maxUserAppCount = dataPermission.getMaxUserAppCount();
        if (maxUserAppCount != null && maxUserAppCount != -1) {
            dataPermission.checkMaxUserAppCount(userAppDomainService.countByCreator(model.getCreatorId()));
        }
        if (model.getSandboxId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSandboxIdRequired);
        }
        if (model.getSandboxId() == CLOUD_SANDBOX_ID) {
            model.setSandboxId(userAppSandboxClient.allocateCloudSandbox(RequestContext.get().getTenantId(), model.getCreatorId()));
            model.setSandboxType(SandboxTypeEnum.Cloud);
        } else {
            model.setSandboxType(requireSandboxType(validateSandbox(model.getSandboxId(), model.getCreatorId())));
        }
        // 数据库账号密码初始化为 dev（密码加密落库；后续可经 save-db-credential 修改）
        model.setDevDbUsername(DEFAULT_DB_CREDENTIAL);
        model.setDevDbPassword(userAppCredentialCipher.encrypt(DEFAULT_DB_CREDENTIAL));
        model.setProdDbUsername(DEFAULT_DB_CREDENTIAL);
        model.setProdDbPassword(userAppCredentialCipher.encrypt(DEFAULT_DB_CREDENTIAL));
        // 发布部署目标默认云端沙箱：显式落值，不依赖 DB 默认
        if (model.getDeployType() == null) {
            model.setDeployType(UserAppDeployType.Platform);
        }
        if (model.getDeployServerId() == null) {
            model.setDeployServerId(UserAppDeployType.CLOUD_SERVER_ID);
        }
        UserAppModel created = userAppDomainService.create(model);
        // 同步登记到用户项目统一表（nameDefined 标记随登记落库）
        UserProjectModel project = userProjectDomainService.register(ProjectTypeEnum.UserApp, created.getId(),
                created.getName(), created.getDescription(), created.getIcon(),
                created.getSpaceId(), created.getCreatorId(), created.getSandboxId(), model.getDevAgentId(), nameDefined);
        // 全栈应用与三方应用一致：登记后即生成 OAuth2 凭证（clientId / clientSecret），无需用户进设置页手动触发。
        // 入参必须是统一表主键：UserApp 对外暴露的项目 id 是 refId（= user_app 主键），不能直接传入
        thirdAppOAuth2ApplicationService.ensureCredential(project.getId());

        // 开发域名绑定（随机前缀 + 平台后缀）
        userAppDomainDomainService.ensureSystemBinding(created.getId(), RequestContext.get().getTenantId(),
                devDomainSuffix, UserAppDomainType.Dev);
        // 生产域名与开发域名同时分配（幂等），发布上架时不再分配
        userAppDomainDomainService.ensureSystemBinding(created.getId(), RequestContext.get().getTenantId(),
                prodDomainSuffix, UserAppDomainType.Prod);

        // 创建者的用户级工作目录登记（同事务）：自定义目录或按规则生成
        userProjectApplicationService.ensureUserWorkspace(model.getCreatorId(), ProjectTypeEnum.UserApp,
                created.getId(), created.getSandboxId(), workspacePath);

        // 资料库协作目录（跨用户读写）：创建 folder 记录 slugId，并在其下创建"项目计划"多维表格记录其 id；
        // 任一步失败仅告警，不阻断项目创建
        try {
            RepoPageRpcDto folder = iRepoPageRpcService.createPage(repoFolderRequest(created.getSpaceId(), RepoResourceTitleSupport.repoFolderTitle(created.getName()),
                    model.getCreatorId(), RequestContext.get().getTenantId()));
            if (folder != null && folder.getId() != null) {
                UserAppModel update = new UserAppModel();
                update.setId(created.getId());
                update.setRepoSlugId(folder.getSlugId());
                // 项目计划多维表格：挂在资料库目录下（与 ensure 补齐路径共用 createPlanTable）
                String planSlugId = createPlanTable(created, folder.getId(), model.getCreatorId(), RequestContext.get().getTenantId());
                if (StrUtil.isNotBlank(planSlugId)) {
                    update.setPlanSlugId(planSlugId);
                    created.setPlanSlugId(planSlugId);
                }
                userAppDomainService.update(update, null);
                created.setRepoSlugId(folder.getSlugId());
            }
        } catch (Exception e) {
            log.warn("Create repo folder/plan table for userapp failed, appId={}", created.getId(), e);
        }
        log.info("Userapp project created, id={}, sandboxId={}", created.getId(), created.getSandboxId());
        return created;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAppModel update(UserAppModel model, UserContext userContext) {
        requireId(model == null ? null : model.getId());
        if (model.getName() != null && StrUtil.isBlank(model.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 全部字段为 null 时 MP 会生成空 SET 子句导致 SQL 语法错误，必须至少更新一个字段
        if (model.getName() == null && model.getDescription() == null
                && model.getIcon() == null && model.getCoverImg() == null) {
            throw new IllegalArgumentException("At least one of name/description/icon/coverImg is required");
        }
        // 改名判断：仅显式传名时才读旧名比对，避免其余更新多一次整行查询；
        // 行不存在由随后的 domain update 抛错（此处判空仅为防NPE）
        boolean nameChanged = false;
        if (model.getName() != null) {
            UserAppModel existing = userAppDomainService.getById(model.getId());
            nameChanged = existing != null && !Objects.equals(model.getName(), existing.getName());
        }
        UserAppModel updated = userAppDomainService.update(model, userContext);
        // 展示字段同步到统一登记表（null 表示不更新，两侧语义一致）。
        // name 必须传调用意图（model.getName()）
        userProjectDomainService.syncBasicInfo(ProjectTypeEnum.UserApp, updated.getId(),
                model.getName(), updated.getDescription(), updated.getIcon());
        // 项目名变更同步资料库目录与计划表标题（提交后执行，失败仅告警，不阻断改名）
        if (nameChanged) {
            repoResourceTitleSupport.syncTitlesAfterCommit(updated.getRepoSlugId(), updated.getPlanSlugId(),
                    updated.getName(), userContext.getUserId(), RequestContext.get().getTenantId());
        }
        return updated;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, UserContext userContext) {
        requireId(id);
        UserAppModel project = userAppDomainService.getById(id);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        // 已预删除的重复删除：直接成功，不重置预删除时间（保留期不延长）
        if (project.getPreDeleteTime() != null) {
            return;
        }
        // 已发布的应用先停掉生产服务，停止失败则中断删除，避免产生无登记的孤儿容器
        if (project.getPublishStatus() == UserAppPublishStatus.Published) {
            stopProd(project, userContext.getUserId());
        }
        // OAuth2 scope 申请单随删全量清理：申请单存统一表主键，须在下面注销登记表前完成换算清理
        // （预删除/immediate 两模式都经过此处；immediate 的 purge 在注销之后，届时 refId 已换算不回主键）
        UserProjectModel userProject = userProjectDomainService.getByRef(ProjectTypeEnum.UserApp, id);
        if (userProject != null) {
            oauth2ScopeApplyDomainService.deleteByProjectId(userProject.getId());
        }
        // immediate：立即彻底删除（服务端配置控制，前端无感知）
        if (DELETE_MODE_IMMEDIATE.equals(deleteMode)) {
            deleteImmediately(project);
            return;
        }
        // 预删除：立即对前端不可见、代理404，保留期满由统一调度任务物理删除
        userAppDomainService.markPreDelete(id, new Date());
        // 统一登记表立即注销（用户项目列表同步不可见）
        userProjectDomainService.unregister(ProjectTypeEnum.UserApp, id);
        // 在途审核单即时清理：预删除对用户已是终态，不能让审核队列死挂到 7 天后 purge
        // purge 内会幂等再清
        publishApplicationService.deletePublishedApply(Published.TargetType.UserApp, id);
        log.info("Userapp project pre-deleted, id={}, purge after {} days", id, PRE_DELETE_RETENTION_DAYS);
    }

    /**
     * 立即彻底删除（userapp.delete.mode=immediate）：工作区目录 + 关联数据（会话/用户关系/发布记录）+ 物理删除行。
     * 清理工作区失败则中断（行未删，可重试）；各删除步骤幂等，中断后重试安全
     */
    private void deleteImmediately(UserAppModel project) {
        Long appId = project.getId();
        // 先清理工作区目录，成功再删行：失败可整体重试，避免产生无法重试的孤儿目录
        if (!cleanupWorkspace(appId)) {
            throw new BizException(ErrorCodeEnum.ERROR_REQUEST.getCode(), "清理工作区目录失败，请稍后重试");
        }
        // 注销统一登记表，标记预删除（供 purge 守卫放行）后物理删除；
        // 关联数据（会话/发布/审核单）与行删除统一收敛在 purge 内，避免两条路径清理项漂移
        userProjectDomainService.unregister(ProjectTypeEnum.UserApp, appId);
        userAppDomainService.markPreDelete(appId, new Date());
        purge(appId);
        log.info("Userapp project deleted immediately, id={}", appId);
    }

    @Override
    public boolean cleanupWorkspace(Long appId) {
        requireId(appId);
        UserAppModel project = userAppDomainService.getById(appId);
        if (project == null) {
            return true;
        }
        if (SandboxTypeEnum.Cloud != project.getSandboxType()) {
            log.info("Userapp personal sandbox, skip workspace cleanup, appId={}, sandboxType={}", appId, project.getSandboxType());
            return true;
        }
        // 按用户项目工作区绑定逐用户删除：不同用户可能使用不同云端沙箱，各自沙箱上的目录都要清理；
        // 各用户的会话用于定位其所在沙箱（deleteWorkspace 按 cId 解析沙箱服务器）
        List<UserProjectWorkspaceModel> workspaces = userProjectWorkspaceDomainService
                .listByProject(ProjectTypeEnum.UserApp.name(), appId);
        if (workspaces.isEmpty()) {
            return true;
        }
        List<ConversationDto> devConversations = conversationApplicationService
                .getConversationListByDevTarget(ProjectTypeEnum.UserApp.name(), appId);
        Map<Long, ConversationDto> conversationByUser = devConversations.stream()
                .filter(conversation -> conversation.getUserId() != null)
                .collect(Collectors.toMap(ConversationDto::getUserId, conversation -> conversation, (a, b) -> a));
        boolean allDeleted = true;
        for (UserProjectWorkspaceModel workspace : workspaces) {
            ConversationDto conversation = conversationByUser.get(workspace.getUserId());
            if (conversation == null) {
                // 该用户无会话：无从定位其沙箱，跳过目录删除（仅随项目删绑定记录）
                log.info("Userapp workspace cleanup skipped, no conversation, appId={}, userId={}", appId, workspace.getUserId());
                continue;
            }
            try {
                agentWorkspaceApplicationService.deleteWorkspace(workspace.getUserId(), conversation.getId());
            } catch (Exception e) {
                allDeleted = false;
                log.warn("Userapp delete workspace failed, appId={}, userId={}, cId={}",
                        appId, workspace.getUserId(), conversation.getId(), e);
            }
        }
        if (allDeleted) {
            userProjectWorkspaceDomainService.deleteByProject(ProjectTypeEnum.UserApp.name(), appId);
        }
        return allDeleted;
    }

    /**
     * 停止生产服务；下游 user_id 取操作者（会话用户）。
     * 端点解析失败（私服离线）只告警放行——无可达目标即无从停容器，
     * 不让已发布应用因私服状态卡死删除；下游停止调用失败仍抛出阻断，避免留下无登记的孤儿容器。
     */
    private void stopProd(UserAppModel project, Long operatorUserId) {
        UserAppSandboxHandle handle;
        try {
            handle = userAppEndpointResolver.resolveProd(project);
        } catch (BizException e) {
            log.warn("Userapp prod endpoint resolve failed, skip stop-before-delete, appId={}, code={}, message={}",
                    project.getId(), e.getCode(), e.getMessage());
            return;
        }
        UserAppCallResult result = userAppSandboxClient.prodStop(handle, project.getId(),
                UserAppDeployCommand.builder().userId(operatorUserId).build());
        if (!result.isSuccess()) {
            // 下游已无该应用，允许删除，否则状态不一致的老项目永远删不掉
            if (UserAppCallResult.ERR_APP_NOT_FOUND.equalsIgnoreCase(result.getCode())) {
                log.info("Userapp prod app missing downstream, treat as stopped, appId={}, code={}, message={}",
                        project.getId(), result.getCode(), result.getMessage());
                return;
            }
            throw new BizException(
                    StrUtil.blankToDefault(result.getCode(), ErrorCodeEnum.ERROR_REQUEST.getCode()),
                    StrUtil.blankToDefault(result.getMessage(), "停止生产服务失败，请稍后重试"));
        }
        log.info("Userapp prod stopped, appId={}", project.getId());
    }

    @Override
    public UserAppModel ensureRepoResources(Long id) {
        UserAppModel project = userAppDomainService.getById(requireId(id));
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        createRepoResources(project);
        return userAppDomainService.getById(id);
    }

    /** 补齐资料库资源（幂等）：目录缺失则建 folder；计划表缺失则在目录下建 datatable；失败仅告警 */
    private void createRepoResources(UserAppModel project) {
        try {
            Long userId = project.getCreatorId();
            Long tenantId = RequestContext.get().getTenantId();
            UserAppModel update = new UserAppModel();
            update.setId(project.getId());
            boolean dirty = false;
            // 目录有效性：无 slugId，或页面已被删除（按 slug 查不到）都视为缺失
            RepoPageRpcDto folder = StrUtil.isNotBlank(project.getRepoSlugId())
                    ? getPageBySlug(project.getRepoSlugId(), userId, tenantId) : null;
            if (folder == null || folder.getId() == null) {
                if (StrUtil.isNotBlank(project.getRepoSlugId())) {
                    log.info("Repo folder deleted, recreate, projectId={}, oldSlugId={}",
                            project.getId(), project.getRepoSlugId());
                }
                RepoPageRpcDto created = iRepoPageRpcService.createPage(
                        repoFolderRequest(project.getSpaceId(), RepoResourceTitleSupport.repoFolderTitle(project.getName()), userId, tenantId));
                if (created != null && StrUtil.isNotBlank(created.getSlugId())) {
                    update.setRepoSlugId(created.getSlugId());
                    dirty = true;
                    // 目录重建则计划表一并重建（旧 planSlugId 指向已不存在的父节点，即使有值也覆盖为新值）
                    if (created.getId() != null) {
                        String planSlugId = createPlanTable(project, created.getId(), userId, tenantId);
                        if (StrUtil.isNotBlank(planSlugId)) {
                            update.setPlanSlugId(planSlugId);
                        }
                    }
                }
            } else {
                // 目录标题对齐：存量旧命名（纯项目名/旧项目名派生）或改名同步失败的补偿入口，
                // 每次进详情都重试直到成功；仅平台生成式标题才对齐，用户自定义标题不动
                repoResourceTitleSupport.alignFolderTitle(folder, project.getName(), userId, tenantId);
                // 计划表按存在性校验（无 slugId 或已被删除则重建）+ 标题懒对齐
                boolean planMissing = true;
                if (StrUtil.isNotBlank(project.getPlanSlugId())) {
                    RepoPageRpcDto plan = getPageBySlug(project.getPlanSlugId(), userId, tenantId);
                    planMissing = plan == null || plan.getId() == null;
                    if (planMissing) {
                        log.info("Plan table deleted, recreate, projectId={}, oldSlugId={}",
                                project.getId(), project.getPlanSlugId());
                    } else {
                        repoResourceTitleSupport.alignPlanTitle(plan, project.getName(), userId, tenantId);
                    }
                }
                if (planMissing) {
                    String planSlugId = createPlanTable(project, folder.getId(), userId, tenantId);
                    if (StrUtil.isNotBlank(planSlugId)) {
                        update.setPlanSlugId(planSlugId);
                        dirty = true;
                    }
                }
            }
            if (dirty) {
                userAppDomainService.update(update, null);
                log.info("Userapp repo resources ensured, projectId={}, repoSlugId={}, planSlugId={}",
                        project.getId(), update.getRepoSlugId(), update.getPlanSlugId());
            }
        } catch (Exception e) {
            // 查询/创建异常时整体跳过（不做任何落库变更），下次进入详情再试
            log.warn("Ensure repo resources failed, projectId={}", project.getId(), e);
        }
    }

    /** 按 slug 反查页面（资料库侧不存在返回 null；RPC 异常向上抛由调用方跳过本轮） */
    private RepoPageRpcDto getPageBySlug(String slugId, Long userId, Long tenantId) {
        RepoPageGetBySlugRequest getBySlug = new RepoPageGetBySlugRequest();
        getBySlug.setSlugId(slugId);
        getBySlug.setUserId(userId);
        getBySlug.setTenantId(tenantId);
        return iRepoPageRpcService.getPageBySlug(getBySlug);
    }

    /** 在目录下创建"项目计划"多维表格，返回 slugId（失败返回 null） */
    private String createPlanTable(UserAppModel project, Long folderPageId, Long userId, Long tenantId) {
        RepoPageCreateRequest planRequest = new RepoPageCreateRequest();
        planRequest.setSpaceId(project.getSpaceId());
        planRequest.setParentId(folderPageId);
        planRequest.setTitle(RepoResourceTitleSupport.planTableTitle(project.getName()));
        planRequest.setPageType("datatable");
        planRequest.setUserId(userId);
        planRequest.setTenantId(tenantId);
        RepoPageRpcDto planTable = iRepoPageRpcService.createPage(planRequest);
        return planTable == null ? null : planTable.getSlugId();
    }

    @Override
    public UserAppModel getById(Long id) {
        requireId(id);
        UserAppModel model = userAppDomainService.getById(id);
        // 预删除项目对前端一律视为不存在（详情/权限校验/运行时操作/会话定位全部收敛于此）
        return model != null && model.getPreDeleteTime() == null ? model : null;
    }

    @Override
    public List<UserAppModel> list(UserAppModel query) {
        return userAppDomainService.list(query);
    }

    @Override
    public SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize) {
        return userAppDomainService.pageQuery(query, current, pageSize);
    }

    // 不需要事务注解
    @Override
    public void recordBuildVersion(Long id, String version, String gitCommit, String packageUrl, UserContext userContext) {
        requireId(id);
        if (StrUtil.isBlank(version)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectBuildVersionInvalid);
        }
        if (StrUtil.isBlank(packageUrl)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectPackageUrlRequired);
        }
        //防御：CAS重试必须读到库内最新数据，事务（RR快照）会让重试必然失败——显式拒绝事务内调用而非依赖注释约定
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("recordBuildVersion 不可在事务内调用：乐观锁重试需读取库内最新数据");
        }
        userAppDomainService.recordBuildVersion(id, version, gitCommit, packageUrl, userContext);
        log.info("Userapp build version recorded, id={}, version={}", id, version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePublishStatus(Long id, UserAppPublishStatus status, UserContext userContext) {
        requireId(id);
        if (status == null) {
            throw new IllegalArgumentException("publishStatus must not be null");
        }
        UserAppModel project = userAppDomainService.getById(id);
        // 已预删除的项目拒绝重新发布（在途市场审核通过不能再把它置为 Published）；
        // 下架回写（Developing）放行，发布记录才能正常清理
        if (project != null && project.getPreDeleteTime() != null
                && UserAppPublishStatus.Published == status) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        // 发布上架前置：必须已部署生产
        if (UserAppPublishStatus.Published == status) {
            requireProdDeployed(project);
        }

        userAppDomainService.updatePublishStatus(id, status, userContext);
        // 发布状态同步统一登记表 user_project（与三方应用同列）：项目列表跨类型统一展示发布状态。
        UserProjectModel registry = userProjectDomainService.getByRef(ProjectTypeEnum.UserApp, id);
        if (registry == null) {
            log.warn("Userapp publish status sync skipped, registry row missing, appId={}, status={}", id, status);
            return;
        }
        UserProjectModel registryUpdate = new UserProjectModel();
        registryUpdate.setId(registry.getId());
        registryUpdate.setPublishStatus(status);
        userProjectDomainService.update(registryUpdate);
    }

    @Override
    public BuildVersionDto checkPublishPrecondition(Long id) {
        requireId(id);
        UserAppModel project = userAppDomainService.getById(id);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        requireProdDeployed(project);
        //发布当前部署的版本（不要求最新版本：最新构建可能尚未部署或部署有问题，解决后再发布）
        BuildVersionDto deployedVersion = project.findBuildVersion(project.getProdReleaseId());
        deployedVersion.requireComplete();
        return deployedVersion;
    }

    /** 发布上架前置条件：必须已部署生产环境且记录了部署版本（提交发布/审核通过/状态回写等各入口共用同一规则） */
    private void requireProdDeployed(UserAppModel project) {
        if (project == null || !Boolean.TRUE.equals(project.getProdDeployed())
                || StrUtil.isBlank(project.getProdReleaseId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPublishRequiresDeployed);
        }
    }

    @Override
    public String saveDbCredential(Long id, boolean devEnv, String username, String password, UserContext userContext) {
        requireId(id);
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectCredentialSaveFailed);
        }
        UserAppModel project = userAppDomainService.getById(id);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        // 先同步重置下游数据库账号，成功再落库（重置为相同值幂等，失败可直接重试）；
        UserAppSandboxHandle handle = userAppEndpointResolver.resolve(project, !devEnv);
        UserAppCallResult result = userAppSandboxClient.resetDbPassword(handle, id, userContext.getUserId(),
                devEnv ? "dev" : "prod", username, password);
        if (!result.isSuccess()) {
            throw new BizException(StrUtil.blankToDefault(result.getCode(), ErrorCodeEnum.ERROR_REQUEST.getCode()),
                    StrUtil.blankToDefault(result.getMessage(), "沙箱数据库账号重置失败"));
        }
        String encrypted = userAppCredentialCipher.encrypt(password);
        userAppDomainService.saveDbCredential(id, devEnv, username, encrypted, userContext);
        log.info("Userapp project db credential saved, id={}, devEnv={}", id, devEnv);
        return result.getMessage();
    }

    @Override
    public String getDbPassword(Long id, boolean devEnv) {
        requireId(id);
        UserAppModel model = userAppDomainService.getById(id);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        String encrypted = devEnv ? model.getDevDbPassword() : model.getProdDbPassword();
        return userAppCredentialCipher.decrypt(encrypted);
    }

    @Override
    public List<UserAppModel> listPreDeleteDue() {
        Date deadline = new Date(System.currentTimeMillis() - PRE_DELETE_RETENTION_DAYS * 24L * 3600_000L);
        return userAppDomainService.listPreDeleteDue(deadline);
    }

    @Override
    public void purge(Long id) {
        requireId(id);
        UserAppModel project = userAppDomainService.getById(id);
        // 仅物理删除已预删除的行；不存在/未预删除直接跳过（幂等）
        if (project == null || project.getPreDeleteTime() == null) {
            return;
        }
        // 全程切换到项目所属租户：会话/发布/审核等关联表带租户过滤
        Long callerTenantId = RequestContext.get().getTenantId();
        boolean tenantSwitched = !Objects.equals(callerTenantId, project.getTenantId());
        if (tenantSwitched) {
            RequestContext.setThreadTenantId(project.getTenantId());
        }
        try {
            // 关联数据随物理删除清理（与 immediate 路径同一套幂等删除；保留期内项目未物理删行，恢复不受影响）：
            int conversations = conversationApplicationService.deleteByDevTarget(ProjectTypeEnum.UserApp.name(), id);
            int relations = publishApplicationService.deleteTargetRelations(Published.TargetType.UserApp, id);
            publishApplicationService.deleteByTarget(Published.TargetType.UserApp, id);
            // 在途审核单：预删除时已即时清过，此处幂等兜底（覆盖 immediate 路径）
            publishApplicationService.deletePublishedApply(Published.TargetType.UserApp, id);
            // 依赖资源先于项目删除（均幂等，失败可重试）：
            userAppDomainDomainService.deleteByAppId(id);
            userAppTaskDomainService.deleteByAppId(id);
            userAppDomainService.delete(id,
                    UserContext.builder().tenantId(project.getTenantId()).build());
            log.info("Userapp project purged, id={}, preDeleteTime={}, conversations={}, relations={}",
                    id, project.getPreDeleteTime(), conversations, relations);
        } finally {
            if (tenantSwitched) {
                RequestContext.setThreadTenantId(callerTenantId);
            }
        }
    }

    /** 校验沙箱并返回其配置（scope 供沙箱类型落库） */
    private SandboxConfigRpcDto validateSandbox(Long sandboxId, Long creatorId) {
        SandboxConfigRpcDto config = sandboxConfigRpcService.queryById(sandboxId);
        if (config == null || Boolean.FALSE.equals(config.getIsActive())
                || (SandboxScopeEnum.USER == config.getScope() && !Objects.equals(config.getUserId(), creatorId))
                || config.getScope() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSandboxInvalid);
        }
        return config;
    }

    /** 沙箱类型必须有值：按沙箱配置 scope 派生，云端分配直接 Cloud */
    private static SandboxTypeEnum requireSandboxType(SandboxConfigRpcDto config) {
        SandboxTypeEnum type = SandboxTypeEnum.fromScopeCode(config.getScope().getCode());
        if (type == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSandboxInvalid);
        }
        return type;
    }

    /** 资料库协作目录创建请求（操作者上下文：repo RPC 以此构建 RequestContext） */
    private static RepoPageCreateRequest repoFolderRequest(Long spaceId, String title, Long userId, Long tenantId) {
        RepoPageCreateRequest request = new RepoPageCreateRequest();
        request.setSpaceId(spaceId);
        request.setTitle(title);
        request.setPageType("folder");
        request.setUserId(userId);
        request.setTenantId(tenantId);
        return request;
    }


    private Long requireId(Long id) {
        if (id == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        return id;
    }
}
