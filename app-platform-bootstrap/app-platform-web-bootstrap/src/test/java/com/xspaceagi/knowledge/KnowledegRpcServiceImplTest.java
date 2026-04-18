package com.xspaceagi.knowledge;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.knowledge.api.KnowledgeConfigRpcService;
import com.xspaceagi.knowledge.api.KnowledgeQaSearchRpcService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeDocumentRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeQaRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeDocumentResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaResponseVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeDocumentSearchRpcService;
import com.xspaceagi.system.spec.common.RequestContext;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
public class KnowledegRpcServiceImplTest {

    @Resource
    private KnowledgeConfigRpcService knowledgeConfigRpcService;

    @Resource
    private IKnowledgeDocumentSearchRpcService knowledgeDocumentSearchRpcService;

    @Resource
    private KnowledgeQaSearchRpcService knowledgeQaSearchRpcService;

    @Test
    public void testCreateKnowledgeConfig() {

        try {
            RequestContext.setThreadTenantId(1L);

            // Arrange
            KnowledgeCreateRequestVo requestVo = KnowledgeCreateRequestVo.builder()
                    .spaceId(1L)
                    .name("Test Knowledge")
                    .description("This is a test knowledge")
                    .dataType(1)
                    .icon("https://example.com/icon.png")
                    .userId(1L)
                    .build();
            // Act
            Long result = knowledgeConfigRpcService.createKnowledgeConfig(requestVo);

            log.info("Create database result, result={}", result);
            // Assert
            assertNotNull(result);
        } finally {
            RequestContext.remove();
        }
    }

    @Test
    public void testQueryKnowledgeConfigById() {
        try {
            RequestContext.setThreadTenantId(1L);
            KnowledgeConfigVo knowledgeConfigVo = knowledgeConfigRpcService.queryKnowledgeConfigById(153L);
            log.info("Knowledge config query result, knowledgeConfigVo={}", JSON.toJSONString(knowledgeConfigVo));
            assertNotNull(knowledgeConfigVo);
        } finally {
            RequestContext.remove();
        }
    }

    /**
     * 测试知识库文档搜索功能
     */
    @Test
    public void testDocumentSearch() {
        try {
            RequestContext.setThreadTenantId(1L);
            
            // Arrange
            KnowledgeDocumentRequestVo requestVo = new KnowledgeDocumentRequestVo();
            requestVo.setKbId(113L); // 使用上面查询的知识库ID
            
            // Act
            KnowledgeDocumentResponseVo responseVo = knowledgeDocumentSearchRpcService.documentSearch(requestVo);
            
            log.info("Knowledge doc search result, responseVo={}", JSON.toJSONString(responseVo));
            
            // Assert
            assertNotNull(responseVo);
            assertNotNull(responseVo.getDocumentVoList());
            log.info("Document count: {}", responseVo.getDocumentVoList().size());
            
        } finally {
            RequestContext.remove();
        }
    }

    /**
     * 测试知识库文档搜索功能 - 测试空结果
     */
    @Test
    public void testDocumentSearchWithEmptyResult() {
        try {
            RequestContext.setThreadTenantId(1L);
            
            // Arrange - 使用一个不存在的知识库ID
            KnowledgeDocumentRequestVo requestVo = new KnowledgeDocumentRequestVo();
            requestVo.setKbId(999999L);
            
            // Act
            KnowledgeDocumentResponseVo responseVo = knowledgeDocumentSearchRpcService.documentSearch(requestVo);
            
            log.info("Knowledge doc search (empty), responseVo={}", JSON.toJSONString(responseVo));
            
            // Assert
            assertNotNull(responseVo);
            assertNotNull(responseVo.getDocumentVoList());
            log.info("Document count: {}", responseVo.getDocumentVoList().size());
            
        } finally {
            RequestContext.remove();
        }
    }

    /**
     * 测试知识库问答搜索功能
     * 测试参数：kbId=41, question="浮点数的定义是什么", 期望docId=24
     */
    @Test
    public void testQaSearch() {
        try {
            RequestContext.setThreadTenantId(1L);
            
            // Arrange - 创建问答搜索请求
            KnowledgeQaRequestVo requestVo = new KnowledgeQaRequestVo();
            requestVo.setKbId(413L);  // 知识库ID
            requestVo.setQuestion("Antigravity");  // 问题
            requestVo.setTopK(5);  // 返回前5个结果
            requestVo.setIgnoreDocStatus(true);  // 不忽略文档状态
            requestVo.setIgnoreTenantId(false);  // 不忽略租户ID
            
            // Act - 执行问答搜索
            KnowledgeQaResponseVo responseVo = knowledgeQaSearchRpcService.search(requestVo);
            
            log.info("Knowledge Q&A search result, responseVo={}", JSON.toJSONString(responseVo));
            
            // Assert - 验证结果
            assertNotNull(responseVo);
            assertNotNull(responseVo.getQaVoList());
            log.info("Q&A result count: {}", responseVo.getQaVoList().size());
            
            // 打印详细结果，特别是验证docId字段
            if (responseVo.getQaVoList() != null && !responseVo.getQaVoList().isEmpty()) {
                responseVo.getQaVoList().forEach(qaVo -> {
                    log.info("Q&A detail - qaId: {}, kbId: {}, docId: {}, question: {}, answer: {}, score: {}", 
                        qaVo.getQaId(), qaVo.getKbId(), qaVo.getDocId(), 
                        qaVo.getQuestion(), qaVo.getAnswer(), qaVo.getScore());
                });
            }
            
        } finally {
            RequestContext.remove();
        }
    }

    /**
     * 测试知识库问答搜索功能 - 测试空结果
     */
    @Test
    public void testQaSearchWithEmptyResult() {
        try {
            RequestContext.setThreadTenantId(1L);
            
            // Arrange - 使用不存在的问题
            KnowledgeQaRequestVo requestVo = new KnowledgeQaRequestVo();
            requestVo.setKbId(41L);  // 知识库ID
            requestVo.setQuestion("一个不存在的问题 xyzabc123");  // 不存在的问题
            requestVo.setTopK(5);  // 返回前5个结果
            requestVo.setIgnoreDocStatus(false);  // 不忽略文档状态
            requestVo.setIgnoreTenantId(false);  // 不忽略租户ID
            
            // Act - 执行问答搜索
            KnowledgeQaResponseVo responseVo = knowledgeQaSearchRpcService.search(requestVo);
            
            log.info("Knowledge Q&A search (empty), responseVo={}", JSON.toJSONString(responseVo));
            
            // Assert - 验证结果
            assertNotNull(responseVo);
            assertNotNull(responseVo.getQaVoList());
            log.info("Q&A result count: {}", responseVo.getQaVoList().size());
            
        } finally {
            RequestContext.remove();
        }
    }

}
