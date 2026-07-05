package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.infra.rpc.GitRpcClient;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.base.ConversationPermissionChecker;
import com.xspaceagi.agent.web.ui.controller.dto.git.*;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

@Tag(name = "Git 版本管理接口", description = "通过 workspaceType 区分两种工作区：taskAgent（通用智能体会话，传 cId）和 pageApp（网页应用，传 projectId）")
@RestController
@RequestMapping("/api/git")
@Slf4j
public class GitController extends BaseController {

    @Resource
    private GitRpcClient gitRpcClient;
    @Resource
    private ICustomPageRpcService iCustomPageRpcService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ConversationPermissionChecker conversationPermissionChecker;

    // ======================== 1. init ========================

    @Operation(summary = "初始化 Git 仓库", description = "在项目目录中执行 git init，并生成 .gitignore。通常无需手动调用，首次 Git 操作时会自动初始化。")
    @PostMapping(value = "/init", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> init(@RequestBody GitBaseReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                gitRpcClient::initTaskAgent, gitRpcClient::initPageApp);
    }

    // ======================== 2. status ========================

    @Operation(summary = "查看工作区状态", description = "返回当前 staged / modified / untracked 文件列表，对应 git status")
    @PostMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> status(@RequestBody GitBaseReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                gitRpcClient::statusTaskAgent, gitRpcClient::statusPageApp);
    }

    // ======================== 3. commit ========================

    @Operation(summary = "提交更改", description = "将暂存区的文件提交为新版本。files 为空时提交全部暂存文件；authorName / authorEmail 由后端自动从登录信息获取，无需前端传递。")
    @PostMapping(value = "/commit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> commit(@RequestBody GitCommitReq req) {
        String authorName = getUser().getUserName();
        String authorEmail = getUser().getEmail();
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.commitTaskAgent(cId, userId, req.getMessage(),
                        req.getFiles(), authorName, authorEmail),
                projectId -> gitRpcClient.commitPageApp(projectId, req.getMessage(),
                        req.getFiles(), authorName, authorEmail));
    }

    // ======================== 4. add ========================

    @Operation(summary = "暂存文件", description = "将文件加入暂存区（git add）。files 为空时暂存全部变更。文件写入/创建后可调用此接口让 Git 跟踪变更。")
    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> add(@RequestBody GitUnstageReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.addTaskAgent(cId, userId, req.getFiles()),
                projectId -> gitRpcClient.addPageApp(projectId, req.getFiles()));
    }

    // ======================== 5. unstage ========================

    @Operation(summary = "取消暂存", description = "将文件从暂存区移回工作区（变为 unstaged），不丢弃文件内容。对应 git restore --staged <files>，files 为空时取消全部暂存。")
    @PostMapping(value = "/unstage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> unstage(@RequestBody GitUnstageReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.unstageTaskAgent(cId, userId, req.getFiles()),
                projectId -> gitRpcClient.unstagePageApp(projectId, req.getFiles()));
    }

    // ======================== 5. discard ========================

    @Operation(summary = "丢弃文件更改", description = "丢弃工作区中未提交的修改，将文件恢复到最近一次 commit 的状态，不可恢复。对应 git restore <files>，files 为空时丢弃全部修改。")
    @PostMapping(value = "/discard", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> discard(@RequestBody GitUnstageReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.discardTaskAgent(cId, userId, req.getFiles()),
                projectId -> gitRpcClient.discardPageApp(projectId, req.getFiles()));
    }

    // ======================== 6. log ========================

    @Operation(summary = "查看提交历史", description = "分页返回 commit 列表，每条包含 hash、message、author、date。page 从 1 开始，默认 pageSize=50。")
    @PostMapping(value = "/log", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> log(@RequestBody GitLogReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (c, u) -> gitRpcClient.logTaskAgent(c, u, req.getPage(), req.getPageSize(), req.getBranch(), req.getFilePath()),
                p -> gitRpcClient.logPageApp(p, req.getPage(), req.getPageSize(), req.getBranch(), req.getFilePath()));
    }

    // ======================== 7. diff ========================

    @Operation(summary = "查看文件差异", description = "对比文件内容差异。source 取值：worktree（工作区 vs HEAD，默认）、staged（暂存区 vs HEAD）、commit（两个 commit 之间，需传 from/to；只传 from 时自动与前一个 commit 比较）。paths 指定文件范围，可空。")
    @PostMapping(value = "/diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> diff(@RequestBody GitDiffReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.diffTaskAgent(cId, userId, req.getSource(), req.getFrom(), req.getTo(), req.getPaths()),
                projectId -> gitRpcClient.diffPageApp(projectId, req.getSource(), req.getFrom(), req.getTo(), req.getPaths()));
    }

    // ======================== 7.5 file-content ========================

    @Operation(summary = "获取指定版本的文件内容", description = "获取指定 git 版本的文件内容，用于 Monaco Diff Editor 等场景的左右对比。ref 取值：worktree（工作区文件）、staged（暂存区文件）、HEAD（最新提交）、commit hash、HEAD~1 等。")
    @PostMapping(value = "/file-content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> fileContent(@RequestBody GitFileContentReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.fileContentTaskAgent(cId, userId, req.getRef(), req.getFilePath()),
                projectId -> gitRpcClient.fileContentPageApp(projectId, req.getRef(), req.getFilePath()));
    }

    // ======================== 8. reset ========================

    @Operation(summary = "重置到指定版本", description = "移动 HEAD 到 target 版本。soft: 后续 commit 的改动保留在暂存区；mixed（默认）: 改动变为 unstaged；hard: 暂存区和工作区全部恢复到 target，后续改动丢失。返回 previousHead 可用于再次 reset 回原版本。")
    @PostMapping(value = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> reset(@RequestBody GitResetReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.resetTaskAgent(cId, userId, req.getTarget(), req.getMode()),
                projectId -> gitRpcClient.resetPageApp(projectId, req.getTarget(), req.getMode()));
    }

    // ======================== 8.5 revert ========================

    @Operation(summary = "回退到指定版本（保留历史）", description = "新建一个 commit 使文件树等于 target 版本，HEAD 向前推进，历史完整保留。与 reset 的区别：reset 会丢掉 target 之后的 commit，revert 不会。要求工作区 clean（未跟踪文件除外）。返回 previousHead 可用于审计。")
    @PostMapping(value = "/revert", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> revert(@RequestBody GitRevertReq req) {
        String authorName = getUser().getUserName();
        String authorEmail = getUser().getEmail();
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.revertTaskAgent(cId, userId, req.getTarget(), req.getMessage(), authorName, authorEmail),
                projectId -> gitRpcClient.revertPageApp(projectId, req.getTarget(), req.getMessage(), authorName, authorEmail));
    }

    // ======================== 9. checkout ========================

    @Operation(summary = "检出目标版本文件", description = "将 target 版本的文件内容恢复到工作区和暂存区，HEAD 不移动（commit 历史不变）。暂存区中的变更是后续 commit 改动的反向，可直接 commit 生成一个回滚提交。")
    @PostMapping(value = "/checkout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> checkout(@RequestBody GitCheckoutReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.checkoutTaskAgent(cId, userId, req.getTarget()),
                projectId -> gitRpcClient.checkoutPageApp(projectId, req.getTarget()));
    }

    // ======================== 11. tags ========================

    @Operation(summary = "查看标签列表", description = "返回所有 tag 名称及其对应的 commit hash")
    @PostMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> tags(@RequestBody GitBaseReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                gitRpcClient::tagsTaskAgent, gitRpcClient::tagsPageApp);
    }

    // ======================== 12. tag-create ========================

    @Operation(summary = "创建标签", description = "在当前 HEAD 上创建轻量标签或附注标签")
    @PostMapping(value = "/tag-create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> tagCreate(@RequestBody GitTagReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.tagCreateTaskAgent(cId, userId, req.getTagName(), req.getMessage()),
                projectId -> gitRpcClient.tagCreatePageApp(projectId, req.getTagName(), req.getMessage()));
    }

    // ======================== 13. tag-delete ========================

    @Operation(summary = "删除标签", description = "删除指定名称的标签")
    @PostMapping(value = "/tag-delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> tagDelete(@RequestBody GitTagReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.tagDeleteTaskAgent(cId, userId, req.getTagName()),
                projectId -> gitRpcClient.tagDeletePageApp(projectId, req.getTagName()));
    }

    // ======================== 14. branches ========================

    @Operation(summary = "查看分支列表", description = "返回所有本地分支名称，标注当前所在分支")
    @PostMapping(value = "/branches", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> branches(@RequestBody GitBaseReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                gitRpcClient::branchesTaskAgent, gitRpcClient::branchesPageApp);
    }

    // ======================== 15. branch-create ========================

    @Operation(summary = "创建分支", description = "基于 startPoint（commit hash / tag / 分支名）创建新分支，startPoint 为空时基于当前 HEAD")
    @PostMapping(value = "/branch-create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> branchCreate(@RequestBody GitBranchReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.branchCreateTaskAgent(cId, userId, req.getBranchName(), req.getStartPoint()),
                projectId -> gitRpcClient.branchCreatePageApp(projectId, req.getBranchName(), req.getStartPoint()));
    }

    // ======================== 16. branch-switch ========================

    @Operation(summary = "切换分支", description = "切换到指定分支，工作区必须干净（无未提交修改）才能切换")
    @PostMapping(value = "/branch-switch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> branchSwitch(@RequestBody GitBranchReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.branchSwitchTaskAgent(cId, userId, req.getBranchName()),
                projectId -> gitRpcClient.branchSwitchPageApp(projectId, req.getBranchName()));
    }

    // ======================== 17. branch-delete ========================

    @Operation(summary = "删除分支", description = "删除指定分支，force=true 强制删除未合并的分支")
    @PostMapping(value = "/branch-delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> branchDelete(@RequestBody GitBranchReq req) {
        return dispatch(req.getWorkspaceType(), req.getCId(), req.getProjectId(),
                (cId, userId) -> gitRpcClient.branchDeleteTaskAgent(cId, userId, req.getBranchName(), req.getForce()),
                projectId -> gitRpcClient.branchDeletePageApp(projectId, req.getBranchName(), req.getForce()));
    }

    // ======================== 权限校验 ========================

    private void checkPageAppAuth(Long projectId) {
        CustomPageDto pageDto = iCustomPageRpcService.queryDetail(projectId);
        if (pageDto == null) {
            throw new IllegalArgumentException("Project not found");
        }
        spacePermissionService.checkSpaceUserPermission(pageDto.getSpaceId());
    }

    // ======================== dispatch ========================

    private ReqResult<Map<String, Object>> dispatch(
            String workspaceType, Long cId, Long projectId,
            BiFunction<Long, Long, Map<String, Object>> taskAgentFn,
            Function<Long, Map<String, Object>> pageAppFn) {

        Map<String, Object> result;
        if ("pageApp".equals(workspaceType)) {
            checkPageAppAuth(projectId);
            result = pageAppFn.apply(projectId);
        } else {
            Long conversationUserId = conversationPermissionChecker.check(cId);
            result = taskAgentFn.apply(cId, conversationUserId);
        }
        return wrapResult(result);
    }

    private ReqResult<Map<String, Object>> wrapResult(Map<String, Object> result) {
        if (result == null) {
            return ReqResult.error("Git operation failed");
        }
        Object successObj = result.get("success");
        Object codeObj = result.get("code");
        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }
        if (!success) {
            String message = result.getOrDefault("message", "Git operation failed").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }
        return ReqResult.success(result);
    }
}
