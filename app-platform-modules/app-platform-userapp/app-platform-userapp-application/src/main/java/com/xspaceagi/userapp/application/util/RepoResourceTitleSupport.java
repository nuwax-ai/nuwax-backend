package com.xspaceagi.userapp.application.util;

import com.xspaceagi.repo.sdk.IRepoPageRpcService;
import com.xspaceagi.repo.sdk.dto.RepoPageGetBySlugRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRenameRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcDto;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Predicate;

/**
 * 项目资料库资源（目录/计划表）标题支持：全栈应用（UserApp）与常规项目（NormalProject）共用的
 * 标题派生规则与改名同步逻辑。
 */
@Slf4j
@Component
public class RepoResourceTitleSupport {

    @Resource
    private IRepoPageRpcService iRepoPageRpcService;

    /** 资料库目录标题（带项目名） */
    public static String repoFolderTitle(String projectName) {
        return StrUtil.format("{}-资料库", projectName);
    }

    /** 项目计划表标题（带项目名） */
    public static String planTableTitle(String projectName) {
        return StrUtil.format("{}-项目计划", projectName);
    }

    /**
     * 目录标题是否为平台生成式（项目名旧格式 或 {名}-资料库）。
     * 重命名/懒对齐只覆盖生成式标题，用户在资料库UI自定义的标题不动
     */
    public static boolean isGeneratedFolderTitle(String title, String projectName) {
        if (StrUtil.isBlank(title)) {
            return false;
        }
        return title.equals(projectName) || title.endsWith("-资料库");
    }

    /** 计划表标题是否为平台生成式（旧固定名"项目计划" 或 {名}-项目计划），用户自定义标题不动 */
    public static boolean isGeneratedPlanTitle(String title) {
        if (StrUtil.isBlank(title)) {
            return false;
        }
        return "项目计划".equals(title) || title.endsWith("-项目计划");
    }

    /**
     * 项目改名后同步资料库目录与计划表标题（slug 不变，按 slug 反查 pageId 重命名；失败仅告警不抛出）。
     * 事务内调用时注册为提交后执行：重命名会触发 ES 索引与树变更通知等网络副作用，
     * 放在事务内会在回滚时留下与库内标题不一致的外部状态，且占住数据库连接做网络IO。
     */
    public void syncTitlesAfterCommit(String repoSlugId, String planSlugId, String projectName, Long userId, Long tenantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncTitles(repoSlugId, planSlugId, projectName, userId, tenantId);
                }
            });
        } else {
            syncTitles(repoSlugId, planSlugId, projectName, userId, tenantId);
        }
    }

    private void syncTitles(String repoSlugId, String planSlugId, String projectName, Long userId, Long tenantId) {
        if (StrUtil.isNotBlank(repoSlugId)) {
            renamePage(repoSlugId, repoFolderTitle(projectName), userId, tenantId,
                    title -> isGeneratedFolderTitle(title, projectName));
        }
        if (StrUtil.isNotBlank(planSlugId)) {
            renamePage(planSlugId, planTableTitle(projectName), userId, tenantId,
                    RepoResourceTitleSupport::isGeneratedPlanTitle);
        }
    }

    /** 按短链ID反查页面并重命名；renameIf 判定当前标题可覆盖（仅平台生成式，用户自定义不动）；失败仅告警：资料库为辅助资源，不阻断主流程 */
    private void renamePage(String slugId, String title, Long userId, Long tenantId, Predicate<String> renameIf) {
        try {
            RepoPageGetBySlugRequest getBySlug = new RepoPageGetBySlugRequest();
            getBySlug.setSlugId(slugId);
            getBySlug.setUserId(userId);
            getBySlug.setTenantId(tenantId);
            RepoPageRpcDto page = iRepoPageRpcService.getPageBySlug(getBySlug);
            if (page == null || page.getId() == null) {
                log.warn("Rename repo page skipped, page not found, slugId={}, title={}", slugId, title);
                return;
            }
            if (!renameIf.test(page.getTitle())) {
                log.info("Rename repo page skipped, custom title kept, slugId={}, current={}", slugId, page.getTitle());
                return;
            }
            if (title.equals(page.getTitle())) {
                return;
            }
            renamePageById(page.getId(), title, userId, tenantId);
        } catch (Exception e) {
            log.warn("Rename repo page failed, slugId={}, title={}", slugId, title, e);
        }
    }

    /** 按页面ID直接重命名（通用原语，是否该改由调用方判定）；失败仅告警不抛出 */
    private void renamePageById(Long pageId, String title, Long userId, Long tenantId) {
        try {
            RepoPageRenameRequest rename = new RepoPageRenameRequest();
            rename.setPageId(pageId);
            rename.setTitle(title);
            rename.setUserId(userId);
            rename.setTenantId(tenantId);
            iRepoPageRpcService.renamePage(rename);
        } catch (Exception e) {
            log.warn("Rename repo page failed, pageId={}, title={}", pageId, title, e);
        }
    }

    /**
     * 资料库目录标题懒对齐：标题与项目名派生的期望值不一致且当前为平台生成式时重命名；
     * 用户自定义标题不动、已一致不动；失败仅告警（下次进入项目详情重试）
     */
    public void alignFolderTitle(RepoPageRpcDto folderPage, String projectName, Long userId, Long tenantId) {
        String expected = repoFolderTitle(projectName);
        if (expected.equals(folderPage.getTitle())
                || !isGeneratedFolderTitle(folderPage.getTitle(), projectName)) {
            return;
        }
        renamePageById(folderPage.getId(), expected, userId, tenantId);
    }

    /**
     * 计划表标题懒对齐：语义同 {@link #alignFolderTitle}
     */
    public void alignPlanTitle(RepoPageRpcDto planPage, String projectName, Long userId, Long tenantId) {
        String expected = planTableTitle(projectName);
        if (expected.equals(planPage.getTitle()) || !isGeneratedPlanTitle(planPage.getTitle())) {
            return;
        }
        renamePageById(planPage.getId(), expected, userId, tenantId);
    }
}
