package com.xspaceagi.knowledge;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.model.TripleDataModel;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IKnowledgeGraphDomainService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.impl.KnowledgeGraphDomainService;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识图谱三元组提取的失败语义测试。
 * <p>
 * 核心约束: 「大模型调用报错」与「大模型正常返回但无三元组」必须区分——
 * 前者上抛(整篇标记失败重试),后者返回空列表(算成功,内容本就无实体关系)。
 * 修复前 extractTriples 把模型异常吞成空列表,导致模型故障被伪装成"无数据"而假成功。
 */
public class KnowledgeGraphTripleExtractTest {

    private KnowledgeGraphDomainService service;
    private ModelApplicationService modelService;

    @BeforeEach
    public void setUp() {
        service = new KnowledgeGraphDomainService();
        modelService = mock(ModelApplicationService.class);
        ReflectionTestUtils.setField(service, "modelApplicationService", modelService);
    }

    /** 大模型调用抛异常 → 必须上抛 KnowledgeException(不可吞成空列表假成功) */
    @Test
    public void modelErrorPropagatesAsKnowledgeException() {
        when(modelService.call(anyString(), anyString(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("Model does not exist or has been deleted: 281"));

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.extractTriplesFromText("某段正文内容"),
                "大模型报错时应上抛,而不是返回空列表假装成功");
        assertTrue(ex.getMessage().contains("281") || ex.getCause() != null,
                "异常应携带原始错误信息");
    }

    /** 大模型正常返回但无三元组 → 返回空列表,不上抛(算成功) */
    @Test
    public void emptyTripleDataIsSuccessNotError() {
        TripleDataModel emptyResult = new TripleDataModel();
        emptyResult.setTripleData(new ArrayList<>());
        when(modelService.call(anyString(), anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(emptyResult);

        List<IKnowledgeGraphDomainService.TripleData> result = service.extractTriplesFromText("某段正文内容");
        assertTrue(result != null && result.isEmpty(),
                "大模型正常返回无三元组时应返回空列表(成功),不应抛异常");
    }

    /** 大模型返回 null → 按无三元组处理(成功),不上抛 */
    @Test
    public void nullModelResponseIsSuccessNotError() {
        when(modelService.call(anyString(), anyString(), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        List<IKnowledgeGraphDomainService.TripleData> result = service.extractTriplesFromText("某段正文内容");
        assertTrue(result != null && result.isEmpty(),
                "大模型返回 null 时按无三元组处理(成功),不应抛异常");
    }
}
