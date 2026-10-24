package com.xspaceagi.repo.web.controller;

import com.xspaceagi.repo.application.service.RepoShareApplicationService;
import com.xspaceagi.repo.application.service.dto.RepoAclStatus;
import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import com.xspaceagi.repo.web.dto.RepoPageAclPutRequest;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档级按人权限控制器（需求7：仅可管理者，开启后未配置成员降为仅阅读）。
 */
@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@Tag(name = "Repo Page ACL", description = "文档协作：文档级按人权限")
public class RepoPageAclController {

    private final RepoShareApplicationService repoShareApplicationService;

    @GetMapping("/pages/{pageId}/acl")
    @Operation(summary = "查询文档级按人权限状况（开关+条目，仅可管理者）")
    public ReqResult<RepoAclStatus> getAcl(@PathVariable Long pageId) {
        return ReqResult.success(repoShareApplicationService.getAcl(pageId));
    }

    @PutMapping("/pages/{pageId}/acl")
    @Operation(summary = "整体覆盖文档级按人权限（仅可管理者；开启后未配置成员降为仅阅读）")
    public ReqResult<Void> putAcl(@PathVariable Long pageId,
                                  @RequestBody RepoPageAclPutRequest request) {
        List<RepoPageAclDomain> entries = new ArrayList<>();
        if (request.getEntries() != null) {
            for (RepoPageAclPutRequest.Entry entry : request.getEntries()) {
                entries.add(RepoPageAclDomain.builder()
                        .pageId(pageId)
                        .userId(entry.getUserId())
                        .permBits(entry.getPermBits())
                        .build());
            }
        }
        repoShareApplicationService.putAcl(pageId, request.getAclEnabled(), entries);
        return ReqResult.success();
    }
}
