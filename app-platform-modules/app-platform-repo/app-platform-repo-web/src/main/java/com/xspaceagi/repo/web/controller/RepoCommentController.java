package com.xspaceagi.repo.web.controller;

import cn.hutool.json.JSONUtil;
import com.xspaceagi.repo.application.service.RepoCommentApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoCommentThread;
import com.xspaceagi.repo.domain.model.RepoCommentDomain;
import com.xspaceagi.repo.web.dto.RepoCommentCreateRequest;
import com.xspaceagi.repo.web.dto.RepoCommentThreadVO;
import com.xspaceagi.repo.web.dto.RepoCommentVO;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档评论控制器（CRUD/解决/@提及通知）。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Comment", description = "文档协作：评论")
public class RepoCommentController {

    private final RepoCommentApplicationService repoCommentApplicationService;

    private final UserApplicationService userApplicationService;

    @GetMapping("/pages/{pageId}/comments")
    @Operation(summary = "查询页面评论线程列表")
    public ReqResult<List<RepoCommentThreadVO>> listCommentThreads(@PathVariable Long pageId,
                                                                   @RequestParam(required = false) String shareToken) {
        List<RepoCommentThread> domainThreads = repoCommentApplicationService.listCommentThreads(pageId, shareToken);
        // 一次性批量解析评论/回复涉及的用户，避免逐条 queryById 的 N+1；查不到回退前端「用户 N」
        Map<Long, UserDto> userById = loadUsers(collectUserIds(domainThreads));
        List<RepoCommentThreadVO> threads = domainThreads.stream()
                .map(thread -> RepoCommentThreadVO.builder()
                        .comment(toVO(thread.getComment(), userById))
                        .replies(thread.getReplies().stream()
                                .map(reply -> toVO(reply, userById))
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());
        return ReqResult.success(threads);
    }

    @PostMapping("/pages/{pageId}/comments")
    @Operation(summary = "发表评论或回复（一层回复），@提及发送站内通知")
    public ReqResult<RepoCommentVO> addComment(@PathVariable Long pageId,
                                               @RequestParam(required = false) String shareToken,
                                               @RequestBody RepoCommentCreateRequest request) {
        RepoCommentDomain comment = repoCommentApplicationService.addComment(
                pageId, request.getParentId(), request.getContent(), request.getMentions(), request.getAnchor(), shareToken);
        return ReqResult.success(toVO(comment, loadUser(comment.getUserId())));
    }

    @PostMapping("/comments/{commentId}/resolve")
    @Operation(summary = "标记评论已解决（限作者或空间管理员/属主）")
    public ReqResult<RepoCommentVO> resolveComment(@PathVariable Long commentId,
                                                   @RequestParam(required = false) String shareToken) {
        RepoCommentDomain comment = repoCommentApplicationService.resolveComment(commentId, shareToken);
        return ReqResult.success(toVO(comment, loadUser(comment.getUserId())));
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "软删除评论及其回复（限作者或空间管理员/属主）")
    public ReqResult<Void> deleteComment(@PathVariable Long commentId,
                                         @RequestParam(required = false) String shareToken) {
        repoCommentApplicationService.deleteComment(commentId, shareToken);
        return ReqResult.success();
    }

    private RepoCommentVO toVO(RepoCommentDomain domain, Map<Long, UserDto> userById) {
        List<Long> mentions = new ArrayList<>();
        if (domain.getMentions() != null && JSONUtil.isTypeJSONArray(domain.getMentions())) {
            mentions = JSONUtil.toList(domain.getMentions(), Long.class);
        }
        UserDto user = userById != null ? userById.get(domain.getUserId()) : null;
        return RepoCommentVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .userId(domain.getUserId())
                .nickName(user != null ? user.getNickName() : null)
                .userName(user != null ? user.getUserName() : null)
                .avatar(user != null ? user.getAvatar() : null)
                .parentId(domain.getParentId())
                .content(domain.getContent())
                .mentions(mentions)
                .anchor(domain.getAnchor())
                .resolved(domain.getResolved())
                .created(domain.getCreated())
                .modified(domain.getModified())
                .build();
    }

    /** 收集线程内所有评论/回复的 userId（含空值防护） */
    private Set<Long> collectUserIds(List<RepoCommentThread> threads) {
        Set<Long> ids = new HashSet<>();
        if (threads == null) return ids;
        for (RepoCommentThread thread : threads) {
            RepoCommentDomain comment = thread.getComment();
            if (comment != null && comment.getUserId() != null) ids.add(comment.getUserId());
            if (thread.getReplies() != null) {
                for (RepoCommentDomain reply : thread.getReplies()) {
                    if (reply.getUserId() != null) ids.add(reply.getUserId());
                }
            }
        }
        return ids;
    }

    /** 批量查用户表返回 id → UserDto；查询失败返回空 map 不阻断（前端回落「用户 N」） */
    private Map<Long, UserDto> loadUsers(Set<Long> ids) {
        Map<Long, UserDto> byId = new HashMap<>();
        if (ids == null || ids.isEmpty()) return byId;
        try {
            List<UserDto> users = userApplicationService.queryUserListByIds(new ArrayList<>(ids));
            if (users != null) {
                for (UserDto user : users) {
                    if (user.getId() != null) byId.put(user.getId(), user);
                }
            }
        } catch (Exception e) {
            log.warn("repo comment load users failed, ids={}", ids, e);
        }
        return byId;
    }

    /** 单条评论的用户解析（发表/解决后即时返回） */
    private Map<Long, UserDto> loadUser(Long userId) {
        Set<Long> ids = new HashSet<>();
        if (userId != null) ids.add(userId);
        return loadUsers(ids);
    }
}
