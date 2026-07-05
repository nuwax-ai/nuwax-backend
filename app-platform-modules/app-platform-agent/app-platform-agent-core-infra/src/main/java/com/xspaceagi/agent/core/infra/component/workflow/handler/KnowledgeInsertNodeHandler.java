package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.KnowledgeInsertNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import com.xspaceagi.knowledge.sdk.vo.DocumentAddRequestVo;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class KnowledgeInsertNodeHandler extends AbstractNodeHandler {

    @Override
    protected Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        KnowledgeInsertNodeConfigDto knowledgeInsertNodeConfigDto = (KnowledgeInsertNodeConfigDto) node.getNodeConfig();
        Map<String, Object> params = extraBindValueMap(workflowContext, node, knowledgeInsertNodeConfigDto.getInputArgs());
        DocumentAddRequestVo documentAddRequestVo = new DocumentAddRequestVo();
        documentAddRequestVo.setKbId(knowledgeInsertNodeConfigDto.getKnowledgeBaseId());
        documentAddRequestVo.setName(params.get("title") == null ? "" : params.get("title").toString());
        documentAddRequestVo.setFileContent(params.get("content") == null ? "" : params.get("content").toString());
        documentAddRequestVo.setDocUrl(params.get("docUrl") == null ? "" : params.get("docUrl").toString());
        try {
            documentAddRequestVo.setSegment(params.get("segment") == null ? SegmentEnum.WORDS : SegmentEnum.valueOf(params.get("segment").toString()));
            documentAddRequestVo.setWords(params.get("words") == null ? null : Integer.parseInt(params.get("words").toString()));
        } catch (Exception e) {
            documentAddRequestVo.setSegment(SegmentEnum.WORDS);
            documentAddRequestVo.setWords(800);
        }
        if (StringUtils.isBlank(documentAddRequestVo.getFileContent()) && StringUtils.isBlank(documentAddRequestVo.getDocUrl())) {
            throw new IllegalArgumentException("Choose either the document content or the document URL");
        }
        Long docId;
        boolean isInRequestContext = RequestContext.get() != null;
        try {
            UserDto user = workflowContext.getAgentContext().getUser();
            if (!isInRequestContext) {
                RequestContext.setThreadTenantId(user.getTenantId());
            }
            documentAddRequestVo.setUserId(user.getId());
            documentAddRequestVo.setUserName(user.getUserName());
            docId = workflowContext.getWorkflowContextServiceHolder().getKnowledgeBaseSearcher().documentAdd(documentAddRequestVo);
        } finally {
            if (!isInRequestContext) {
                RequestContext.remove();
            }
        }
        Map<String, Object> output = new HashMap<>();
        output.put("success", true);
        output.put("docId", docId);
        return output;
    }
}
