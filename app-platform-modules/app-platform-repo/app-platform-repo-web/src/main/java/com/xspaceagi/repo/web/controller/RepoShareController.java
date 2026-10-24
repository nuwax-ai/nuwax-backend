package com.xspaceagi.repo.web.controller;

import com.xspaceagi.repo.application.service.RepoShareApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoShareLinkStatsVO;
import com.xspaceagi.repo.application.service.dto.RepoShareStatus;
import com.xspaceagi.repo.application.service.dto.RepoSpaceShareItemVO;
import com.xspaceagi.repo.web.dto.RepoShareLinkCreateRequest;
import com.xspaceagi.repo.web.dto.RepoShareLinkVO;
import com.xspaceagi.repo.web.dto.RepoShareSpacesRequest;
import com.xspaceagi.repo.web.dto.RepoShareUsersRequest;
import com.xspaceagi.repo.web.dto.RepoUserSearchVO;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分享控制器（需求4：分享到工作空间 + 分享链接）。
 * <p>
 * 权限门槛在应用服务内经 RepoAccessService 校验：
 * 查状况=可查看；发起分享/建链接=可分享；单条取消/删链接=可管理或记录创建者；全量取消=可管理。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Share", description = "文档协作：分享到空间/分享链接")
public class RepoShareController {

    /**
     * 用户搜索返回条数上限
     */
    private static final int USER_SEARCH_MAX_SIZE = 20;

    private final RepoShareApplicationService repoShareApplicationService;

    private final UserApplicationService userApplicationService;

    @GetMapping("/users/search")
    @Operation(summary = "按用户名/昵称搜索用户（需求3分享给个人选人用；登录态即可，排除自己，上限20条）")
    public ReqResult<List<RepoUserSearchVO>> searchUsers(@RequestParam String kw,
                                                         @RequestParam(defaultValue = "10") Integer size) {
        int limit = size == null || size <= 0 ? 10 : Math.min(size, USER_SEARCH_MAX_SIZE);
        Long selfId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        List<RepoUserSearchVO> result = new ArrayList<>();
        for (UserDto user : userApplicationService.searchUsers(kw, limit)) {
            if (user.getId() != null && user.getId().equals(selfId)) {
                continue;
            }
            result.add(RepoUserSearchVO.builder()
                    .id(user.getId())
                    .userName(user.getUserName())
                    .nickName(user.getNickName())
                    .avatar(user.getAvatar())
                    .build());
        }
        return ReqResult.success(result);
    }

    @GetMapping("/pages/{pageId}/shares")
    @Operation(summary = "查询文档分享状况（空间分享+链接+管理标志，可查看者可调）")
    public ReqResult<RepoShareStatus> getShareStatus(@PathVariable Long pageId) {
        return ReqResult.success(repoShareApplicationService.getShareStatus(pageId));
    }

    @PostMapping("/pages/{pageId}/shares/spaces")
    @Operation(summary = "分享文档到工作空间（权限分享非拷贝，重复分享覆盖权限位）")
    public ReqResult<Void> shareToSpaces(@PathVariable Long pageId,
                                         @RequestBody RepoShareSpacesRequest request) {
        repoShareApplicationService.shareToSpaces(pageId, request.getTargetSpaceIds(), request.getPermBits());
        return ReqResult.success();
    }

    @PostMapping("/pages/{pageId}/shares/users")
    @Operation(summary = "分享文档给个人（需求3；重复分享覆盖权限位，成功后站内通知被分享者）")
    public ReqResult<Void> shareToUsers(@PathVariable Long pageId,
                                        @RequestBody RepoShareUsersRequest request) {
        repoShareApplicationService.shareToUsers(pageId, request.getTargetUserIds(), request.getPermBits());
        return ReqResult.success();
    }

    @GetMapping("/spaces/{spaceId}/shares/received")
    @Operation(summary = "空间主页「接收分享」：分享到该空间的文档列表（仅空间成员，无权静默过滤）")
    public ReqResult<List<RepoSpaceShareItemVO>> listSpaceReceivedShares(@PathVariable Long spaceId) {
        return ReqResult.success(repoShareApplicationService.listSpaceReceivedShares(spaceId));
    }

    @GetMapping("/spaces/{spaceId}/shares/sent")
    @Operation(summary = "空间主页「对外分享」：该空间文档分享到其他空间的记录列表（仅空间成员）")
    public ReqResult<List<RepoSpaceShareItemVO>> listSpaceSentShares(@PathVariable Long spaceId) {
        return ReqResult.success(repoShareApplicationService.listSpaceSentShares(spaceId));
    }

    @DeleteMapping("/shares/{shareId}")
    @Operation(summary = "取消单条空间分享（可管理者或分享创建者）")
    public ReqResult<Void> unshare(@PathVariable Long shareId, @RequestParam Long pageId) {
        repoShareApplicationService.unshare(pageId, shareId);
        return ReqResult.success();
    }

    @DeleteMapping("/shares/users/{shareUserId}")
    @Operation(summary = "取消单条个人分享（需求3；可管理者或分享创建者）")
    public ReqResult<Void> unshareUser(@PathVariable Long shareUserId, @RequestParam Long pageId) {
        repoShareApplicationService.unshareUser(pageId, shareUserId);
        return ReqResult.success();
    }

    @PutMapping("/shares/{shareId}/permissions")
    @Operation(summary = "修改单条空间分享的权限位（授权管理；可管理者或分享创建者）")
    public ReqResult<Void> updateSharePerm(@PathVariable Long shareId,
                                           @RequestParam Long pageId,
                                           @RequestParam Integer permBits) {
        repoShareApplicationService.updateSharePerm(pageId, shareId, permBits);
        return ReqResult.success();
    }

    @PutMapping("/shares/users/{shareUserId}/permissions")
    @Operation(summary = "修改单条个人授权的权限位（授权管理；可管理者或记录创建者，不发通知）")
    public ReqResult<Void> updateShareUserPerm(@PathVariable Long shareUserId,
                                               @RequestParam Long pageId,
                                               @RequestParam Integer permBits) {
        repoShareApplicationService.updateShareUserPerm(pageId, shareUserId, permBits);
        return ReqResult.success();
    }

    @DeleteMapping("/pages/{pageId}/shares/spaces")
    @Operation(summary = "取消该文档全部分享与链接（仅可管理者）")
    public ReqResult<Void> unshareAll(@PathVariable Long pageId) {
        repoShareApplicationService.unshareAll(pageId);
        return ReqResult.success();
    }

    @PostMapping("/pages/{pageId}/shares/links")
    @Operation(summary = "新建分享链接（accessType=login|public；allowComment 控制评论；permBits 只读类权限位）")
    public ReqResult<RepoShareLinkVO> createShareLink(@PathVariable Long pageId,
                                                      @RequestBody RepoShareLinkCreateRequest request) {
        var link = repoShareApplicationService.createShareLink(pageId, request.getAccessType(),
                request.getAllowComment(), request.getPermBits());
        return ReqResult.success(RepoShareLinkVO.from(link));
    }

    @PutMapping("/shares/links/{linkId}/comment")
    @Operation(summary = "更新分享链接的评论权限（需求5；可管理者或链接创建者）")
    public ReqResult<Void> updateShareLinkComment(@PathVariable Long linkId,
                                                  @RequestParam Long pageId,
                                                  @RequestParam Integer allowComment) {
        repoShareApplicationService.updateShareLinkComment(pageId, linkId, allowComment);
        return ReqResult.success();
    }

    @DeleteMapping("/shares/links/{linkId}")
    @Operation(summary = "删除分享链接（可管理者或链接创建者）")
    public ReqResult<Void> deleteShareLink(@PathVariable Long linkId, @RequestParam Long pageId) {
        repoShareApplicationService.deleteShareLink(pageId, linkId);
        return ReqResult.success();
    }

    @GetMapping("/pages/{pageId}/share-links/{linkId}/stats")
    @Operation(summary = "分享链接访问统计（访问次数 + 去重访问用户列表及最近访问时间；可查看者可调）")
    public ReqResult<RepoShareLinkStatsVO> getShareLinkStats(@PathVariable Long pageId, @PathVariable Long linkId) {
        return ReqResult.success(repoShareApplicationService.getShareLinkStats(pageId, linkId));
    }
}
