package com.xspaceagi.repo.web.controller;

import com.xspaceagi.repo.application.service.RepoAiEditConversationService;
import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.web.dto.RepoAiEditSessionVO;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 文档 AI 编辑会话端点：点击「AI 编辑」图标时，前端传 slugId 获取内嵌会话地址。
 */
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
public class RepoAiEditController {

    private final RepoPageApplicationService repoPageApplicationService;

    private final RepoAiEditConversationService repoAiEditConversationService;

    @GetMapping("/pages/{slugId}/ai-edit")
    public ReqResult<RepoAiEditSessionVO> getAiEditSession(@PathVariable String slugId,
                                                           @RequestParam(required = false) String shareToken) {
        // getPageBySlug 内部已做 requireViewable 权限裁决（无权抛 403，不存在返回 null）
        RepoPageDomain page = repoPageApplicationService.getPageBySlug(slugId, shareToken);
        if (page == null) {
            return ReqResult.error("page not found");
        }
        Long conversationId = repoAiEditConversationService.getOrCreateConversationId(page);
        Long editAgentId = ((TenantConfigDto) RequestContext.get().getTenantConfig()).getDocEditAgentId();
        String url = "/app/chat/" + editAgentId + "/" + conversationId + "?hideMenu=true&hideNew=true&hideTitle=true&hideTerminal=true&hideTree=true";
        return ReqResult.success(RepoAiEditSessionVO.builder()
                .conversationId(conversationId)
                .url(url)
                .build());
    }
}
