package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeDocumentRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeDocumentResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeDocumentVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeConfigRpcService;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeDocumentSearchRpcService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRpcService {

    private static final int MAX_DOCUMENT_COUNT = 50;
    @Resource
    private IKnowledgeConfigRpcService iKnowledgeConfigRpcService;

    @Resource
    private IKnowledgeDocumentSearchRpcService iKnowledgeDocumentSearchRpcService;

    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    public Long createKnowledgeConfig(KnowledgeCreateRequestVo createRequestVo, Long originalId) {
        String key = "Knowledge";
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        Object knowledgeId = CopyRelationCacheUtil.get(key, createRequestVo.getSpaceId(), originalId);
        if (knowledgeId != null) {
            return (Long) knowledgeId;
        }

        // 检查用户知识库数量
        UserDataPermissionDto userDataPermission = TenantFunctions.callWithIgnoreCheck(() -> userDataPermissionRpcService.getUserDataPermission(createRequestVo.getUserId()));
        userDataPermission.checkMaxKnowledgeCount(TenantFunctions.callWithIgnoreCheck(() -> iKnowledgeConfigRpcService.countTotalKnowledge(createRequestVo.getUserId()).intValue()));

        String newName = copyIndexRecordRepository.newCopyName("table", originalId, createRequestVo.getName());
        createRequestVo.setName(newName);
        knowledgeId = iKnowledgeConfigRpcService.createKnowledgeConfig(createRequestVo);
        CopyRelationCacheUtil.put(key, createRequestVo.getSpaceId(), originalId, knowledgeId);
        return (Long) knowledgeId;
    }


    public KnowledgeConfigVo queryKnowledgeConfigById(Long id) {
        KnowledgeConfigVo knowledgeConfigVo = iKnowledgeConfigRpcService.queryKnowledgeConfigById(id);
        if (knowledgeConfigVo != null) {
            String summary = queryKnowledgeDocumentsSummaryById(id);
            knowledgeConfigVo.setDescription(StringUtils.isNotBlank(knowledgeConfigVo.getDescription()) ? knowledgeConfigVo.getDescription() + "-" + summary : summary);
        }
        return knowledgeConfigVo;
    }

    public String queryKnowledgeDocumentsSummaryById(Long id) {
        KnowledgeDocumentRequestVo requestVo = new KnowledgeDocumentRequestVo();
        requestVo.setKbId(id);
        KnowledgeDocumentResponseVo knowledgeDocumentResponseVo = iKnowledgeDocumentSearchRpcService.documentSearch(requestVo);
        StringBuilder stringBuilder = new StringBuilder();
        int count = 0;
        if (CollectionUtils.isNotEmpty(knowledgeDocumentResponseVo.getDocumentVoList())) {
            stringBuilder.append("The knowledge base contains the following document names：");
            for (KnowledgeDocumentVo documentVo : knowledgeDocumentResponseVo.getDocumentVoList()) {
                if (count >= MAX_DOCUMENT_COUNT) {
                    break;
                }
                stringBuilder.append("`").append(documentVo.getName()).append("`");
                count++;
            }

        }
        return stringBuilder.toString();
    }
}
