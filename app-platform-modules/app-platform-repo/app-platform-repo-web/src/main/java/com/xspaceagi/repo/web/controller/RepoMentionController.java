package com.xspaceagi.repo.web.controller;

import com.xspaceagi.im.sdk.rpc.IImContactRpcService;
import com.xspaceagi.im.sdk.rpc.dto.ImContactItemDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;
import com.xspaceagi.repo.application.service.RepoShareApplicationService;
import com.xspaceagi.repo.web.dto.RepoMentionGrantRequest;
import com.xspaceagi.repo.web.dto.RepoMentionUserVO;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * @ 提及控制器：候选人范围与默认授权。
 * <p>
 * 候选人 = 「我所在的所有<b>团队空间</b>（type=Team）的成员」∪「我的 IM 联系人（status=1 已互为联系人）」。
 * 个人空间（Personal）、私聊（DM）、群组（Group）空间的人一律不进候选：个人空间只有自己，
 * 私聊/群组由 IM 侧维护、动辄上千个，既不构成「同事关系」，整行展开也只会把列表冲爆。
 * 两个来源都不是「平台全量用户」：@ 一个人等于给他开这份文档的查看权限，
 * 范围必须收敛在「我有社交关系的人」里，否则等于把文档链接发给陌生人。
 * <p>
 * IM 那一侧走 {@link IImContactRpcService}（RPC {@code contacts/list}），
 * 用 {@link ObjectProvider} 注入：IM 未部署 / 未配置时该 Bean 仍然存在但调用会抛
 * {@code ImRpcException}，这里整体降级为「只返回空间成员」，不让 @ 功能跟着挂掉。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Mention", description = "文档协作：@ 提及候选人与默认授权")
public class RepoMentionController {

    /** 单次返回条数上限：长列表前端要一次渲染，200 条以内还能滚得动；更多靠 kw 搜索收敛 */
    private static final int MAX_RESULT = 200;

    private static final String SOURCE_SPACE = "space";

    private static final String SOURCE_CONTACT = "contact";

    private final SpaceApplicationService spaceApplicationService;

    private final RepoShareApplicationService repoShareApplicationService;

    private final ObjectProvider<IImContactRpcService> imContactRpcServiceProvider;

    /**
     * @ 候选人列表。
     * <p>
     * {@code spaceId} 只影响排序与 {@code inCurrentSpace} 标记（当前文档空间的人排最前），
     * 不限制候选人范围——不传也照样返回「我所有空间 + 联系人」。
     */
    @GetMapping("/mention/users")
    @Operation(summary = "@ 候选人：我的团队空间（type=Team）成员 + 我的 IM 联系人（当前文档空间排最前）")
    public ReqResult<List<RepoMentionUserVO>> listMentionUsers(
            @RequestParam(value = "spaceId", required = false) Long spaceId,
            @RequestParam(value = "kw", required = false) String kw) {
        Long selfId = RequestContext.get().getUserId();
        Long tenantId = RequestContext.get().getTenantId();
        // 只取团队空间：个人空间没有「其他人」，私聊/群组空间的人不是同事关系，都不能当 @ 候选。
        // 这里用 queryVisibleListByUserId 而不是 querySpaceIdList —— 后者会把名下上千个 DM/Group
        // 也查回来，还得在内存里再丢一遍；前者把 Personal/Team 白名单下推到了 SQL。
        List<Long> mySpaceIds = teamSpaceIdsOf(selfId);
        if (mySpaceIds == null) {
            mySpaceIds = new ArrayList<>();
        }
        Long currentSpaceId = spaceId != null && mySpaceIds.contains(spaceId) ? spaceId
                : (mySpaceIds.isEmpty() ? null : mySpaceIds.get(0));

        Map<Long, RepoMentionUserVO> byId = new LinkedHashMap<>();

        // ---------- 1）空间成员：当前文档空间优先，其余按空间顺序 ----------
        if (currentSpaceId != null) {
            collectSpaceUsers(byId, currentSpaceId, true, selfId);
        }
        for (Long mySpaceId : mySpaceIds) {
            if (Objects.equals(mySpaceId, currentSpaceId)) {
                continue;
            }
            collectSpaceUsers(byId, mySpaceId, false, selfId);
        }

        // ---------- 2）IM 联系人（只取 status=1 已互为联系人；智能体跳过） ----------
        collectContacts(byId, selfId, tenantId);

        String keyword = kw == null ? "" : kw.trim().toLowerCase(Locale.ROOT);
        List<RepoMentionUserVO> result = new ArrayList<>();
        for (RepoMentionUserVO user : byId.values()) {
            if (!keyword.isEmpty() && !matches(user, keyword)) {
                continue;
            }
            result.add(user);
        }
        // 当前空间 → 其他空间 → 联系人；同组内按展示名排，便于肉眼查找
        result.sort(Comparator
                .comparingInt(RepoMentionController::groupOf)
                .thenComparing(user -> nameOf(user).toLowerCase(Locale.ROOT)));
        if (result.size() > MAX_RESULT) {
            log.info("mention users truncated: total={}, limit={}, kw={}", result.size(), MAX_RESULT, kw);
            result = new ArrayList<>(result.subList(0, MAX_RESULT));
        }
        return ReqResult.success(result);
    }

    /**
     * @ 提及默认授权：被提及的人获得「可查看 + 可评论」（空间内成员无需额外授权）。
     * 幂等，前端在每次选中候选人后调用；失败不影响提及本身。
     */
    @PostMapping("/pages/{pageId}/mentions/grant")
    @Operation(summary = "@ 提及默认授权：非空间成员补「可查看+可评论」个人分享（幂等、不降级、不发通知）")
    public ReqResult<Integer> grantMentionAccess(@PathVariable Long pageId,
                                                 @RequestBody RepoMentionGrantRequest request) {
        List<Long> userIds = request == null ? null : request.getUserIds();
        return ReqResult.success(repoShareApplicationService.grantMentionAccess(pageId, userIds));
    }

    // ================================================================== 内部

    /**
     * 用户所在、且类型为团队（{@link Space.Type#Team}）的空间 id。
     * <p>
     * 查询复用平台「可见空间」口径（SQL 已按 Personal/Team 白名单过滤），这里再收敛到 Team：
     * 个人空间只有自己，私聊/群组是 IM 内部空间，都不是「我的空间同事」。
     */
    private List<Long> teamSpaceIdsOf(Long userId) {
        List<SpaceDto> spaces = spaceApplicationService.queryVisibleListByUserId(userId);
        if (spaces == null || spaces.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(spaces.size());
        for (SpaceDto space : spaces) {
            if (space != null && space.getId() != null && space.getType() == Space.Type.Team) {
                ids.add(space.getId());
            }
        }
        return ids;
    }

    private void collectSpaceUsers(Map<Long, RepoMentionUserVO> byId, Long spaceId, boolean current, Long selfId) {
        List<SpaceUserDto> users;
        try {
            users = spaceApplicationService.querySpaceUserList(spaceId);
        } catch (Exception e) {
            log.warn("mention: query space users failed, spaceId={}", spaceId, e);
            return;
        }
        if (users == null) {
            return;
        }
        for (SpaceUserDto user : users) {
            if (user == null || user.getUserId() == null || user.getUserId().equals(selfId)) {
                continue;
            }
            // 同一人可能在多个空间：已有记录只补「当前空间」标记，不覆盖已有来源
            RepoMentionUserVO existing = byId.get(user.getUserId());
            if (existing != null) {
                if (current) {
                    existing.setInCurrentSpace(true);
                    existing.setSpaceId(spaceId);
                }
                continue;
            }
            byId.put(user.getUserId(), RepoMentionUserVO.builder()
                    .userId(user.getUserId())
                    .userName(user.getUserName())
                    .nickName(user.getNickName() == null || user.getNickName().isBlank()
                            ? user.getUserName() : user.getNickName())
                    .avatar(user.getAvatar())
                    .source(SOURCE_SPACE)
                    .inCurrentSpace(current)
                    .spaceId(spaceId)
                    .build());
        }
    }

    private void collectContacts(Map<Long, RepoMentionUserVO> byId, Long selfId, Long tenantId) {
        IImContactRpcService contactRpc = imContactRpcServiceProvider.getIfAvailable();
        if (contactRpc == null) {
            return;
        }
        ImContactListDto contacts;
        try {
            contacts = contactRpc.listContacts(ImContactListRequest.builder()
                    .actor(selfId, tenantId, null)
                    .build());
        } catch (Exception e) {
            // IM 未部署 / 未配置 / 超时：静默降级，@ 仍可用（只是没有联系人）
            log.warn("mention: list IM contacts failed, degrade to space users only, userId={}", selfId, e);
            return;
        }
        if (contacts == null) {
            return;
        }
        if (contacts.truncatedOrFalse()) {
            log.info("mention: IM contacts truncated at {} for userId={}", ImContactListDto.MAX_CONTACTS, selfId);
        }
        for (ImContactItemDto contact : contacts.acceptedOnly()) {
            if (contact.userId() == null || Objects.equals(contact.userId(), selfId) || contact.agent()) {
                continue;
            }
            // 已经是空间成员的，保留空间来源（本来就有权限，不必显示成「联系人」）
            if (byId.containsKey(contact.userId())) {
                continue;
            }
            byId.put(contact.userId(), RepoMentionUserVO.builder()
                    .userId(contact.userId())
                    .userName(contact.userName())
                    // 服务端算好的展示名（备注名 > 昵称 > 账号名）直接当昵称用，避免前端重算
                    .nickName(contact.displayNameOrFallback())
                    .avatar(contact.avatar())
                    .source(SOURCE_CONTACT)
                    .inCurrentSpace(false)
                    .build());
        }
    }

    private static boolean matches(RepoMentionUserVO user, String keyword) {
        return contains(user.getNickName(), keyword)
                || contains(user.getUserName(), keyword)
                || contains(nameOf(user), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String nameOf(RepoMentionUserVO user) {
        if (user.getNickName() != null && !user.getNickName().isBlank()) {
            return user.getNickName();
        }
        return user.getUserName() == null ? "" : user.getUserName();
    }

    private static int groupOf(RepoMentionUserVO user) {
        if (Boolean.TRUE.equals(user.getInCurrentSpace())) {
            return 0;
        }
        return SOURCE_SPACE.equals(user.getSource()) ? 1 : 2;
    }
}