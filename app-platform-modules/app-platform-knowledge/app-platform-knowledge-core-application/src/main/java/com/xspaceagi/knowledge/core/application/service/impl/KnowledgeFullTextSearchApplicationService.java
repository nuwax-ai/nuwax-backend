package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSearchApplicationService;
import com.xspaceagi.knowledge.core.spec.dto.fulltext.FullTextSearchRequestDto;
import com.xspaceagi.knowledge.core.spec.dto.fulltext.FullTextSearchResultDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextSearchRequestModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextSearchResultModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextStatsModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库全文检索应用服务实现
 * 
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@Service
public class KnowledgeFullTextSearchApplicationService implements IKnowledgeFullTextSearchApplicationService {

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private SpacePermissionService spacePermissionService;

    /**
     * 全文检索
     * 
     * @param request 检索请求
     * @param userContext 用户上下文
     * @return 检索结果列表
     */
    public List<FullTextSearchResultModel> search(FullTextSearchRequestModel request, UserContext userContext) {
        log.info("Full-text search: kbId={}, query={}, userId={}", 
            request.getKbId(), request.getQueryText(), userContext.getUserId());

        // 1. 查询知识库信息
        KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(request.getKbId());
        if (config == null) {
            throw new IllegalArgumentException("Knowledge base does not exist: kbId=" + request.getKbId());
        }

        // 2. 权限校验
        spacePermissionService.checkSpaceUserPermission(config.getSpaceId());

        // 3. 设置租户ID和空间ID
        request.setTenantId(userContext.getTenantId());
        request.setSpaceId(config.getSpaceId());

        // 4. 调用 Domain 服务执行检索
        List<FullTextSearchResultModel> results = fullTextSearchDomainService.search(request);

        log.info("Full-text done: kbId={}, resultCount={}", request.getKbId(), results.size());

        return results;
    }

    /**
     * 全文检索（使用 spec DTO，供 Controller 调用，需要权限校验）
     * 
     * @param requestDto 检索请求 DTO
     * @param userContext 用户上下文
     * @return 检索结果列表
     */
    public List<FullTextSearchResultDto> searchFullText(FullTextSearchRequestDto requestDto, UserContext userContext) {
        log.info("Full-text search: kbId={}, query={}, userId={}", 
            requestDto.getKbId(), requestDto.getQueryText(), userContext.getUserId());

        // 1. 转换 DTO 为 Domain Model
        FullTextSearchRequestModel requestModel = new FullTextSearchRequestModel();
        requestModel.setKbId(requestDto.getKbId());
        requestModel.setQueryText(requestDto.getQueryText());
        requestModel.setTopK(requestDto.getTopK());
        requestModel.setDocIds(requestDto.getDocIds());
        requestModel.setIgnoreDocStatus(requestDto.getIgnoreDocStatus());

        // 2. 调用内部方法
        List<FullTextSearchResultModel> results = search(requestModel, userContext);

        // 3. 转换为 spec DTO
        return results.stream()
            .map(this::convertToDto)
            .collect(Collectors.toList());
    }

    /**
     * 全文检索（供 RPC 服务调用，不做权限校验）
     * 
     * @param requestModel 检索请求 Model（已包含 tenantId）
     * @param tenantId 租户ID
     * @return 检索结果列表
     */
    @Override
    public List<FullTextSearchResultModel> searchFullTextForRpc(FullTextSearchRequestModel requestModel, Long tenantId) {
        log.info("Full-text (RPC): tenantId={}, kbIds={}, query={}", 
            tenantId, requestModel.getKbIds(), requestModel.getQueryText());

        // 1. 处理 kbIds（支持多个知识库或不传）
        if (requestModel.getKbIds() != null && !requestModel.getKbIds().isEmpty()) {
            // 如果传了 kbIds，查询第一个知识库获取空间ID（假设同一租户的知识库在同一空间）
            KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(requestModel.getKbIds().get(0));
            if (config != null) {
                requestModel.setSpaceId(config.getSpaceId());
            }
        }
        // 如果没有传 kbIds，则不限制知识库，spaceId 保持为 null

        // 2. 设置租户ID（tenantId从参数获取，不依赖UserContext）
        requestModel.setTenantId(tenantId);

        // 3. 调用 Domain 服务执行检索（不做权限校验）
        List<FullTextSearchResultModel> results = fullTextSearchDomainService.search(requestModel);

        log.info("Full-text done: kbIds={}, resultCount={}", requestModel.getKbIds(), results.size());

        return results;
    }

    /**
     * 获取统计信息
     * 
     * @param kbId 知识库ID（可选）
     * @param userContext 用户上下文
     * @return 统计信息
     */
    public FullTextStatsModel getStats(Long kbId, UserContext userContext) {
        log.info("FTS stats: kbId={}, userId={}", kbId, userContext.getUserId());

        Long spaceId = null;

        // 如果指定了知识库ID，需要校验权限
        if (kbId != null) {
            KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(kbId);
            if (config == null) {
                throw new IllegalArgumentException("Knowledge base does not exist: kbId=" + kbId);
            }
            spacePermissionService.checkSpaceUserPermission(config.getSpaceId());
            spaceId = config.getSpaceId();
        }

        // 调用 Domain 服务获取统计信息
        FullTextStatsModel stats = fullTextSearchDomainService.getStats(
            userContext.getTenantId(), 
            kbId, 
            spaceId
        );

        log.info("Stats OK: docCount={}, totalSegments={}", 
            stats.getDocCount(), stats.getTotalSegments());

        return stats;
    }

    /**
     * 转换 Domain Model 为 spec DTO
     */
    private FullTextSearchResultDto convertToDto(FullTextSearchResultModel model) {
        return FullTextSearchResultDto.builder()
            .rawSegmentId(model.getRawSegmentId())
            .docId(model.getDocId())
            .kbId(model.getKbId())
            .rawText(model.getRawText())
            .sortIndex(model.getSortIndex())
            .score(model.getScore())
            .documentName(model.getDocumentName())
            .build();
    }
}
