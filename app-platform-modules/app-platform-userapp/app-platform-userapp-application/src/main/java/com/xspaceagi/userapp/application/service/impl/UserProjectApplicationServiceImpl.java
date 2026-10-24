package com.xspaceagi.userapp.application.service.impl;

import cn.hutool.core.util.StrUtil;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.repo.sdk.IRepoPageRpcService;
import com.xspaceagi.repo.sdk.dto.RepoPageCreateRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetBySlugRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcDto;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.application.service.IUserProjectApplicationService;
import com.xspaceagi.userapp.application.util.RepoResourceTitleSupport;
import com.xspaceagi.userapp.application.util.WorkspacePathValidator;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.model.UserProjectWorkspaceModel;
import com.xspaceagi.userapp.domain.service.IOAuth2ScopeApplyDomainService;
import com.xspaceagi.userapp.domain.service.IUserProjectDomainService;
import com.xspaceagi.userapp.domain.service.IUserProjectWorkspaceDomainService;
import com.xspaceagi.userapp.sdk.constant.ProjectDefaultNames;
import com.xspaceagi.userapp.sdk.dto.SandboxTypeEnum;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class UserProjectApplicationServiceImpl implements IUserProjectApplicationService {

    private static final Long CLOUD_SANDBOX_ID = -1L;
    private static final String DEFAULT_PROJECT_EN_NAME = "Unnamed project";
    private static final String DEFAULT_PROJECT_CN_NAME = "未命名项目";
    private static final int PROJECT_NAME_MAX_LENGTH = 128;

    @Resource
    private IUserProjectDomainService userProjectDomainService;
    @Resource
    private IUserProjectWorkspaceDomainService userProjectWorkspaceDomainService;
    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;
    @Resource
    private IRepoPageRpcService iRepoPageRpcService;
    @Resource
    private RepoResourceTitleSupport repoResourceTitleSupport;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;
    @Resource
    private IUserAppSandboxClient userAppSandboxClient;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private IOAuth2ScopeApplyDomainService oauth2ScopeApplyDomainService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel register(ProjectTypeEnum projectType, Long refId, String name, String description,
            String icon, Long spaceId, Long creatorId, Long sandboxId, Long devAgentId, Boolean nameDefined) {
        if (projectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectTypeInvalid);
        }
        // 登记语义是「源表记录 → 统一表」的映射，故要求有引用表且 refId 非空；
        // 无引用表的类型（常规项目、三方应用）各有专属 create 入口，不能走这里，
        // 否则会落出 refId 与「项目 id 即本表主键」约定相矛盾的坏数据。
        if (!projectType.hasRefTable() || refId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectRefRequired);
        }
        if (spaceId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSpaceIdRequired);
        }
        if (creatorId == null) {
            throw new IllegalArgumentException("creatorId is required");
        }
        return userProjectDomainService.register(projectType, refId, name, description, icon, spaceId, creatorId, sandboxId, devAgentId, nameDefined);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncBasicInfo(ProjectTypeEnum projectType, Long refId, String name, String description, String icon) {
        if (projectType == null || refId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectRefRequired);
        }
        userProjectDomainService.syncBasicInfo(projectType, refId, name, description, icon);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unregister(ProjectTypeEnum projectType, Long refId) {
        if (projectType == null || refId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectRefRequired);
        }
        userProjectDomainService.unregister(projectType, refId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel createNormal(UserProjectModel model, String workspacePath) {
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 名称定制标记：显式命名且非默认名才 true（防中间层把兜底名当显式名漏下来）
        model.setNameDefined(!StrUtil.isBlank(model.getName()) && !ProjectDefaultNames.isDefault(model.getName()));
        // 名称缺省按语言取默认名（工作目录已移至用户×项目绑定表，项目行不再存目录）
        if (StrUtil.isBlank(model.getName())) {
            model.setName(defaultProjectName(null));
        }
        if (model.getSpaceId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSpaceIdRequired);
        }
        if (model.getCreatorId() == null) {
            throw new IllegalArgumentException("creatorId is required");
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
        model.setProjectType(ProjectTypeEnum.NormalProject);
        model.setRefId(null);
        UserProjectModel created = userProjectDomainService.create(model);
        // 创建者的用户级工作目录登记（同事务）：仅个人沙箱自定义目录落值，其余留空走下游默认
        ensureUserWorkspace(model.getCreatorId(), ProjectTypeEnum.NormalProject, created.getId(),
                created.getSandboxId(), workspacePath);
        // 资料库协作目录（跨用户读写）：创建 folder 记录 slugId，并在其下创建"项目计划"多维表格记录 slugId；
        // 任一步失败仅告警，不阻断项目创建
        try {
            RepoPageRpcDto folder = iRepoPageRpcService.createPage(repoFolderRequest(created.getSpaceId(), RepoResourceTitleSupport.repoFolderTitle(created.getName()),
                    model.getCreatorId(), RequestContext.get().getTenantId()));
            if (folder != null && folder.getId() != null) {
                UserProjectModel update = new UserProjectModel();
                update.setId(created.getId());
                update.setRepoSlugId(folder.getSlugId());
                // 项目计划多维表格：挂在资料库目录下（与 ensure 补齐路径共用 createPlanTable）
                String planSlugId = createPlanTable(created, folder.getId(), model.getCreatorId(), RequestContext.get().getTenantId());
                if (StrUtil.isNotBlank(planSlugId)) {
                    update.setPlanSlugId(planSlugId);
                    created.setPlanSlugId(planSlugId);
                }
                userProjectDomainService.update(update);
                created.setRepoSlugId(folder.getSlugId());
            }
        } catch (Exception e) {
            log.warn("Create repo folder/plan table for normal project failed, projectId={}", created.getId(), e);
        }
        log.info("Normal user project created, id={}, sandboxId={}", created.getId(), created.getSandboxId());
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel createThirdApp(UserProjectModel model) {
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 同 createNormal：显式命名且非默认名才 true
        model.setNameDefined(!StrUtil.isBlank(model.getName()) && !ProjectDefaultNames.isDefault(model.getName()));
        if (StrUtil.isBlank(model.getName())) {
            model.setName(defaultProjectName(null));
        }
        if (model.getSpaceId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectSpaceIdRequired);
        }
        if (model.getCreatorId() == null) {
            throw new IllegalArgumentException("creatorId is required");
        }
        // 三方应用无底层引用表：项目 id 即本表主键，refId 恒为空；
        // 非开发态项目，不分配沙箱（sandbox_id 留空、sandbox_type 取库默认值），
        // 也不建工作目录与资料库协作目录。OAuth2 凭证由上层按需生成。
        model.setProjectType(ProjectTypeEnum.ThirdApp);
        model.setRefId(null);
        UserProjectModel created = userProjectDomainService.create(model);
        log.info("Third app user project created, id={}, spaceId={}, creatorId={}", created.getId(),
                created.getSpaceId(), created.getCreatorId());
        return created;
    }


    @Override
    public UserProjectWorkspaceModel ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId, Long sandboxId, String workspacePath) {
        if (userId == null || projectType == null || projectId == null || sandboxId == null) {
            throw new IllegalArgumentException("userId/projectType/projectId/sandboxId are required");
        }
        // 同用户同项目唯一目录：已有绑定直接返回，忽略传入的沙箱/目录
        UserProjectWorkspaceModel existing = userProjectWorkspaceDomainService.getByUserProject(userId, projectType.name(), projectId);
        if (existing != null) {
            return existing;
        }
        // 仅「个人沙箱 + 自定义目录」落双路径（agent 执行与文件访问同机同路径，恒等）；
        boolean custom = StringUtils.isNotBlank(workspacePath);
        String path = null;
        if (custom) {
            SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryById(sandboxId);
            if (sandboxConfig == null || sandboxConfig.getScope() != SandboxScopeEnum.USER) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                        "Custom workspace directory is only supported on personal sandboxes");
            }
            path = WorkspacePathValidator.normalize(workspacePath);
            UserProjectWorkspaceModel occupied = userProjectWorkspaceDomainService.getOccupied(sandboxId, path);
            if (occupied != null) {
                log.warn("Workspace occupied, userId={}, type={}, sandboxId={}, path={}, occupiedType={}, occupiedProject={}",
                        userId, projectType, sandboxId, path, occupied.getProjectType(), occupied.getProjectId());
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectWorkspaceOccupied);
            }
        }
        UserProjectWorkspaceModel binding = new UserProjectWorkspaceModel();
        binding.setTenantId(RequestContext.get().getTenantId());
        binding.setUserId(userId);
        binding.setProjectType(projectType.name());
        binding.setProjectId(projectId);
        binding.setSandboxId(sandboxId);
        binding.setFileWorkspacePath(path);
        binding.setAgentWorkspacePath(path);
        try {
            userProjectWorkspaceDomainService.add(binding);
            log.info("User project workspace bound, userId={}, type={}, projectId={}, path={}",
                    userId, projectType, projectId, path);
            return binding;
        } catch (DuplicateKeyException e) {
            // 并发兜底（uk_user_project：同用户同项目并发插入）：重查返回；重查为空说明非该索引冲突，原样抛出
            UserProjectWorkspaceModel concurrent = userProjectWorkspaceDomainService.getByUserProject(userId, projectType.name(), projectId);
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel updateNormal(Long id, String name, String description, String icon) {
        UserProjectModel existing = requireById(id);
        requireNormal(existing);
        if (name != null && StrUtil.isBlank(name)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 全部字段为 null 时 MP 会生成空 SET 子句导致 SQL 语法错误，必须至少更新一个字段
        if (name == null && description == null && icon == null) {
            throw new IllegalArgumentException("At least one of name/description/icon is required");
        }
        UserProjectModel update = new UserProjectModel();
        update.setId(id);
        update.setName(name);
        update.setDescription(description);
        update.setIcon(icon);
        // 仅当本次显式传入名称时才视为已定制
        if (name != null) {
            update.setNameDefined(true);
        }
        UserProjectModel updated = userProjectDomainService.update(update);
        // 项目名变更同步资料库目录与计划表标题（提交后执行，失败仅告警，不阻断改名；与全栈应用一致）
        if (name != null && !Objects.equals(name, existing.getName())) {
            repoResourceTitleSupport.syncTitlesAfterCommit(updated.getRepoSlugId(), updated.getPlanSlugId(),
                    updated.getName(), RequestContext.get().getUserId(), RequestContext.get().getTenantId());
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel updateThirdApp(Long id, String name, String description, String icon) {
        UserProjectModel existing = requireById(id);
        requireThirdApp(existing);
        if (name != null && StrUtil.isBlank(name)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNameRequired);
        }
        // 全部字段为 null 时 MP 会生成空 SET 子句导致 SQL 语法错误，必须至少更新一个字段
        if (name == null && description == null && icon == null) {
            throw new IllegalArgumentException("At least one of name/description/icon is required");
        }
        UserProjectModel update = new UserProjectModel();
        update.setId(id);
        update.setName(name);
        update.setDescription(description);
        update.setIcon(icon);
        // 仅当本次显式传入名称时才视为已定制
        if (name != null) {
            update.setNameDefined(true);
        }
        UserProjectModel updated = userProjectDomainService.update(update);
        log.info("Third app user project basic info updated, id={}", id);
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNormal(Long id) {
        UserProjectModel existing = requireById(id);
        requireNormal(existing);
        userProjectDomainService.delete(id);
        log.info("Normal user project deleted, id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteThirdApp(Long id) {
        UserProjectModel existing = requireById(id);
        requireThirdApp(existing);
        // 市场数据随删清理：发布记录、统计、收藏/最近使用关系，以及在途审核单
        // （否则删后审核队列残留死单：通过必失败于前置校验，拒绝还会给已删应用 owner 发通知）
        publishApplicationService.deleteTargetRelations(Published.TargetType.ThirdApp, id);
        publishApplicationService.deleteByTarget(Published.TargetType.ThirdApp, id);
        publishApplicationService.deletePublishedApply(Published.TargetType.ThirdApp, id);
        // OAuth2 scope 申请单随删全量清理：项目已删，历史单无从关联（审核列表 name/clientId 全空）
        oauth2ScopeApplyDomainService.deleteByProjectId(id);
        userProjectDomainService.delete(id);
        log.info("Third app user project deleted, id={}, clientId={}", id, existing.getOauthClientId());
    }

    @Override
    public void checkThirdAppPublishPrecondition(Long id) {
        UserProjectModel existing = requireById(id);
        requireThirdApp(existing);
        // 三方应用没有部署概念，发布就绪 = OAuth2 凭证已生成（创建/首次打开设置页即自动生成）
        if (StrUtil.isBlank(existing.getOauthClientId()) || StrUtil.isBlank(existing.getOauthClientSecret())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.thirdAppPublishRequiresCredential);
        }
    }

    @Override
    public void updateThirdAppPublishStatus(Long id, UserAppPublishStatus status) {
        requireId(id);
        if (status == null) {
            throw new IllegalArgumentException("publishStatus must not be null");
        }
        UserProjectModel existing = userProjectDomainService.getById(id);
        // 上架回写守卫：源行已删或非三方应用时抛错中断审核事务，
        // 防止与删除并发时给已删应用提交孤儿市场记录
        if (UserAppPublishStatus.Published == status
                && (existing == null || existing.getProjectType() != ProjectTypeEnum.ThirdApp)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        // 其余状态（Applying/拒绝与下架回退的 Developing）：源行已删则告警忽略，
        // 保证拒绝/下架后的发布记录清理不被源行缺失阻断
        if (existing == null || existing.getProjectType() != ProjectTypeEnum.ThirdApp) {
            log.warn("Third app publish status write-back skipped, project missing or not ThirdApp, id={}, status={}", id, status);
            return;
        }
        UserProjectModel update = new UserProjectModel();
        update.setId(id);
        update.setPublishStatus(status);
        userProjectDomainService.update(update);
        log.info("Third app publish status updated, id={}, status={}", id, status);
    }

    @Override
    public UserProjectModel ensureRepoResources(Long id) {
        UserProjectModel project = userProjectDomainService.getById(requireId(id));
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        createRepoResources(project);
        return userProjectDomainService.getById(id);
    }

    /** 补齐资料库资源（幂等）：目录/计划表无 slugId 或页面已被删除（按 slug 查不到）则重建并落新 slugId；失败仅告警 */
    private void createRepoResources(UserProjectModel project) {
        try {
            Long userId = project.getCreatorId();
            Long tenantId = RequestContext.get().getTenantId();
            UserProjectModel update = new UserProjectModel();
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
                userProjectDomainService.update(update);
                log.info("Normal project repo resources ensured, projectId={}, repoSlugId={}, planSlugId={}",
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
    private String createPlanTable(UserProjectModel project, Long folderPageId, Long userId, Long tenantId) {
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
    public UserProjectModel getById(Long id) {
        return userProjectDomainService.getById(requireId(id));
    }

    @Override
    public UserProjectModel getByRef(ProjectTypeEnum projectType, Long refId) {
        if (projectType == null || refId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectRefRequired);
        }
        return userProjectDomainService.getByRef(projectType, refId);
    }

    @Override
    public List<UserProjectModel> listByRefs(ProjectTypeEnum projectType, List<Long> refIds) {
        if (projectType == null || refIds == null || refIds.isEmpty()) {
            return List.of();
        }
        UserProjectModel query = new UserProjectModel();
        query.setProjectType(projectType);
        query.setRefIds(refIds);
        return userProjectDomainService.list(query);
    }

    @Override
    public List<UserProjectModel> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        UserProjectModel query = new UserProjectModel();
        query.setIds(ids);
        return userProjectDomainService.list(query);
    }

    @Override
    public UserProjectWorkspaceModel getUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId) {
        return userProjectWorkspaceDomainService.getByUserProject(userId, projectType.name(), projectId);
    }

    @Override
    public UserProjectWorkspaceModel ensureUserWorkspace(Long userId, ProjectTypeEnum projectType, Long projectId) {
        UserProjectModel project = queryByProjectId(projectType, projectId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
        }
        return ensureUserWorkspace(userId, projectType, projectId, project.getSandboxId(), null);
    }

    @Override
    public String validateConversationWorkspace(Long userId, Long projectId, Long sandboxId, String workspacePath) {
        if (userId == null || projectId == null || sandboxId == null) {
            throw new IllegalArgumentException("userId/projectId/sandboxId are required");
        }
        // 沙箱须存在、启用、个人沙箱属于本人（复用项目侧校验，防拿他人沙箱当会话目录宿主）；云端哨兵 -1 查不到也在此拦截
        SandboxConfigRpcDto sandboxConfig = validateSandbox(sandboxId, userId);
        // 自定义目录要求 agent 执行与文件访问同机同路径：仅个人沙箱
        if (sandboxConfig.getScope() != SandboxScopeEnum.USER) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "Custom workspace directory is only supported on personal sandboxes");
        }
        String path = WorkspacePathValidator.normalize(workspacePath);
        if (path == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "workspacePath is required");
        }
        // 会话目录不得占用其他项目的绑定目录（跨项目串数据）；本项目自有绑定不算占用（等价于显式继承）；
        // 会话之间共享同一目录是允许的
        UserProjectWorkspaceModel occupied = userProjectWorkspaceDomainService.getOccupied(sandboxId, path);
        if (occupied != null
                && !(ProjectTypeEnum.NormalProject.name().equals(occupied.getProjectType()) && projectId.equals(occupied.getProjectId()))) {
            log.warn("Conversation workspace occupied by project binding, userId={}, sandboxId={}, path={}, occupiedType={}, occupiedProject={}",
                    userId, sandboxId, path, occupied.getProjectType(), occupied.getProjectId());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectWorkspaceOccupied);
        }
        return path;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProjectModel updateFlags(Long id, Boolean pinned, Boolean archived) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        UserProjectModel existing = userProjectDomainService.getById(id);
        if (existing == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        UserProjectModel update = new UserProjectModel();
        update.setId(id);
        if (pinned != null) {
            update.setPinned(pinned);
        }
        if (archived != null) {
            update.setArchived(archived);
        }
        userProjectDomainService.update(update);
        return userProjectDomainService.getById(id);
    }

    @Override
    public UserProjectModel queryByProjectId(ProjectTypeEnum projectType, Long projectId) {
        if (projectType == null || projectId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectRefRequired);
        }
        // 无引用表的类型（常规项目、三方应用）项目 id 即统一表主键
        if (!projectType.hasRefTable()) {
            return userProjectDomainService.getById(projectId);
        }
        return userProjectDomainService.getByRef(projectType, projectId);
    }

    @Override
    public SuperPage<UserProjectModel> pageQuery(UserProjectModel query, Long current, Long pageSize) {
        return userProjectDomainService.pageQuery(query, current, pageSize);
    }

    /** 资料库协作目录创建请求（pageType=folder） */
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
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
        }
        return id;
    }

    private UserProjectModel requireById(Long id) {
        UserProjectModel model = userProjectDomainService.getById(requireId(id));
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
        }
        return model;
    }

    /** UserApp/PageApp 的登记行由各自业务流程维护，常规项目接口不可操作 */
    private void requireNormal(UserProjectModel model) {
        if (model.getProjectType() != ProjectTypeEnum.NormalProject) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNormalOnly);
        }
    }

    /** 其它类型的展示信息由各自业务流程维护，三方应用接口不可操作 */
    private void requireThirdApp(UserProjectModel model) {
        if (model.getProjectType() != ProjectTypeEnum.ThirdApp) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectThirdAppOnly);
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

    /** 项目名缺省：优先取工作目录最后一级文件夹名（超长截断），取不到按语言返回默认名 */
    private String defaultProjectName(String workspacePath) {
        if (workspacePath != null) {
            // 分隔符统一为 '/'，抹掉结尾斜杠后取最后一段
            String trimmed = workspacePath.replaceAll("/+$", "");
            int idx = trimmed.lastIndexOf('/');
            String segment = idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
            // 空段或裸盘符（如 "C:"）不算有效名称
            if (StrUtil.isNotBlank(segment) && !segment.matches("^[A-Za-z]:$")) {
                return segment.length() > PROJECT_NAME_MAX_LENGTH
                        ? segment.substring(0, PROJECT_NAME_MAX_LENGTH) : segment;
            }
        }
        return "en-US".equalsIgnoreCase(RequestContext.get().getLang())
                ? DEFAULT_PROJECT_EN_NAME : DEFAULT_PROJECT_CN_NAME;
    }
}
