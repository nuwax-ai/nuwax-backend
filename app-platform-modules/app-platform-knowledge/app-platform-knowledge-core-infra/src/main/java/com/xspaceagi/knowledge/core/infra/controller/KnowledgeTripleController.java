package com.xspaceagi.knowledge.core.infra.controller;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.docparser.SurrealDbService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IGraphTripleSyncTaskService;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.domain.dto.task.AutoRecordTask;
import com.xspaceagi.knowledge.domain.knowledgegraph.service.IKnowledgeGraphDomainExtService;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeDocumentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskRepository;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IKnowledgeGraphDomainService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.converter.TripleConverter;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto.KnowledgeGraphDeleteRequest;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto.KnowledgeGraphGenerateRequest;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto.KnowledgeGraphListRequest;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto.KnowledgeTriplePageRequest;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.dto.TripleQueryRequest;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo.DocumentGraphVo;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo.KnowledgeGraphListItemVo;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo.KnowledgeGraphStatsVo;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo.KnowledgeTriplePageVo;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.triple.vo.TripleVo;
import com.xspaceagi.knowledge.domain.service.impl.KnowledgeTaskDomainService;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识图谱三元组接口
 *
 * @author system
 * @date 2025-04-14
 */
@Tag(name = "知识库-知识图谱接口")
@RestController
@RequestMapping("/api/knowledge/triple")
@Slf4j
public class KnowledgeTripleController {

    @Resource
    private SurrealDbService surrealDbService;

    @Resource
    private IKnowledgeDocumentRepository knowledgeDocumentRepository;

    @Resource
    private IDocumentParseStatusRepository documentParseStatusRepository;

    @Resource
    private IKnowledgeGraphDomainService knowledgeGraphDomainService;

    @Resource
    private TripleConverter tripleConverter;

    @Resource
    private IGraphTripleSyncTaskService graphTripleSyncTaskService;

    @Resource
    private KnowledgeTaskDomainService knowledgeTaskDomainService;

    @Resource
    private IKnowledgeGraphDomainExtService knowledgeGraphDomainExtService;

    @Resource
    private IKnowledgeTaskRepository knowledgeTaskRepository;

    /**
     * 按知识库ID分页查询三元组
     */
    @Operation(summary = "按知识库ID分页查询三元组")
    @PostMapping("/list/byKnowledge")
    public ReqResult<KnowledgeTriplePageVo> listByKnowledgeId(@RequestBody KnowledgeTriplePageRequest request) {
        try {
            if (request == null || request.getKnowledgeId() == null) {
                return ReqResult.error("知识库ID不能为空");
            }

            // 设置默认值
            Integer pageNum = request.getPageNum() != null && request.getPageNum() > 0 ? request.getPageNum() : 1;
            Integer pageSize = request.getPageSize() != null && request.getPageSize() > 0 ? request.getPageSize() : 10;

            // 查询总数
            Long total = surrealDbService.countTriplesByKnowledgeId(request.getKnowledgeId());

            // 查询分页数据
            String jsonResult = surrealDbService.queryTriplesByKnowledgeIdPage(
                    request.getKnowledgeId(), pageNum, pageSize);
            List<TripleVo> triples = tripleConverter.parseTripleResult(jsonResult);

            // 组装分页结果
            KnowledgeTriplePageVo pageVo = new KnowledgeTriplePageVo();
            pageVo.setList(triples);
            pageVo.setTotal(total);
            pageVo.setPageNum(pageNum);
            pageVo.setPageSize(pageSize);
            pageVo.setTotalPages((int) Math.ceil((double) total / pageSize));

            return ReqResult.success(pageVo);
        } catch (Exception e) {
            log.error("查询知识库三元组失败, request={}", request, e);
            return ReqResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 按文档ID查询三元组
     */
    @Operation(summary = "按文档ID查询三元组")
    @GetMapping("/list/byDocument/{documentId}")
    public ReqResult<DocumentGraphVo> listByDocumentId(@PathVariable Long documentId) {
        try {
            String jsonResult = surrealDbService.queryTriplesByDocumentId(documentId);
            List<TripleVo> triples = tripleConverter.parseTripleResult(jsonResult);
            DocumentGraphVo vo = tripleConverter.convertToDocumentGraphVo(documentId, triples);
            return ReqResult.success(vo);
        } catch (Exception e) {
            log.error("查询文档三元组失败, documentId={}", documentId, e);
            return ReqResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 按条件查询三元组
     */
    @Operation(summary = "按条件查询三元组")
    @PostMapping("/list")
    public ReqResult<List<TripleVo>> list(@RequestBody TripleQueryRequest request) {
        try {
            String jsonResult;

            if (request.getDocumentId() != null) {
                jsonResult = surrealDbService.queryTriplesByDocumentId(request.getDocumentId());
            } else if (request.getKnowledgeId() != null) {
                jsonResult = surrealDbService.queryTriplesByKnowledgeId(request.getKnowledgeId());
            } else if (StringUtils.isNotBlank(request.getDocumentName())) {
                jsonResult = surrealDbService.queryTriplesByDocumentName(request.getDocumentName());
            } else {
                jsonResult = surrealDbService.queryAllTriplesWithDocument();
            }

            List<TripleVo> triples = tripleConverter.parseTripleResult(jsonResult);
            return ReqResult.success(triples);
        } catch (Exception e) {
            log.error("查询三元组失败", e);
            return ReqResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询知识图谱统计信息
     */
    @Operation(summary = "查询知识图谱统计信息")
    @GetMapping("/stats/{knowledgeId}")
    public ReqResult<KnowledgeGraphStatsVo> stats(@PathVariable Long knowledgeId) {
        try {
            // 查询三元组统计
            String statsResult = surrealDbService.statsByKnowledgeId(knowledgeId);
            Integer tripleCount = tripleConverter.extractCount(statsResult);

            // 查询谓语统计
            String predicateResult = surrealDbService.predicateStatsByKnowledgeId(knowledgeId);
            List<KnowledgeGraphStatsVo.PredicateStats> predicateStats = tripleConverter.parsePredicateStats(predicateResult);

            // 查询文档数量
            String documentResult = surrealDbService.queryDocumentsByKnowledgeId(knowledgeId);
            List<?> docs = tripleConverter.parseResultArray(documentResult);
            Integer documentCount = docs != null ? docs.size() : 0;

            KnowledgeGraphStatsVo statsVo = tripleConverter.convertToKnowledgeGraphStatsVo(
                    knowledgeId, tripleCount, predicateStats, documentCount);
            return ReqResult.success(statsVo);
        } catch (Exception e) {
            log.error("查询知识图谱统计失败, knowledgeId={}", knowledgeId, e);
            return ReqResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 删除文档的知识图谱
     * 同时删除 SurrealDB 三元组数据和 MySQL 状态记录
     */
    @Operation(summary = "删除文档的知识图谱")
    @PostMapping("/graph/delete")
    public ReqResult<Boolean> deleteGraphByDocumentId(@RequestBody KnowledgeGraphDeleteRequest request) {
        try {
            if (request == null || request.getKbId() == null || request.getDocumentId() == null) {
                return ReqResult.error("知识库ID和文档ID不能为空");
            }

            Long documentId = request.getDocumentId();

            // 1. 删除 SurrealDB 中该文档的所有三元组
            boolean surrealDbSuccess = surrealDbService.deleteTriplesByDocumentId(documentId);
            log.info("删除 SurrealDB 三元组: documentId={}, success={}", documentId, surrealDbSuccess);

            // 2. 删除 MySQL 中该文档的状态记录
            documentParseStatusRepository.deleteByDocumentId(documentId);
            log.info("删除 MySQL 状态记录: documentId={}", documentId);

            return ReqResult.success(true);
        } catch (Exception e) {
            log.error("删除文档知识图谱失败, request={}", request, e);
            return ReqResult.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 查询知识图谱列表
     */
    @Operation(summary = "查询知识图谱列表")
    @PostMapping("/graph/list")
    public ReqResult<List<KnowledgeGraphListItemVo>> graphList(
            @RequestBody KnowledgeGraphListRequest request) {
        try {
            if (request == null || request.getKnowledgeId() == null) {
                return ReqResult.error("知识库ID不能为空");
            }

            // 构建查询条件（mapper只支持kbId和name）
            Map<String, Object> queryMap = new HashMap<>();
            queryMap.put("kbId", request.getKnowledgeId());
            if (StringUtils.isNotBlank(request.getName())) {
                queryMap.put("name", request.getName());
            }

            // 构建排序条件
            List<OrderItem> orderItems = buildOrderItems(request.getCreatedOrder(), request.getUpdatedOrder());

            // 查询文档列表
            List<KnowledgeDocumentModel> docList = knowledgeDocumentRepository.pageQuery(
                    queryMap, orderItems, 0L, 1000L);

            // 固定过滤：只查询 docStatus=2（分析成功）的文档
            docList = docList.stream()
                    .filter(doc -> doc.getDocStatus() != null
                            && doc.getDocStatus().getCode() == 2)
                    .collect(Collectors.toList());

            // 获取文档ID列表
            List<Long> documentIds = docList.stream()
                    .map(KnowledgeDocumentModel::getId)
                    .collect(Collectors.toList());

            // 批量查询三元组解析状态
            Map<Long, Integer> tripleStatusMap = new HashMap<>();
            if (!documentIds.isEmpty()) {
                List<DocumentParseStatusModel> statusList = documentParseStatusRepository.queryListByDocumentIds(documentIds);
                tripleStatusMap = tripleConverter.convertToTripleStatusMap(statusList);
            }

            // 按 tripleStatus 筛选（如有条件）
            final Map<Long, Integer> finalTripleStatusMap = tripleStatusMap;
            if (request.getTripleStatus() != null) {
                docList = docList.stream()
                        .filter(doc -> request.getTripleStatus().equals(
                                finalTripleStatusMap.getOrDefault(doc.getId(), 0)))
                        .collect(Collectors.toList());
            }

            // 按 fileType 筛选（如有条件）
            if (StringUtils.isNotBlank(request.getFileType())) {
                docList = docList.stream()
                        .filter(doc -> {
                            String fileType = tripleConverter.extractFileType(doc.getDocUrl());
                            return request.getFileType().equalsIgnoreCase(fileType);
                        })
                        .collect(Collectors.toList());
            }

            // 查询tripleStatus为1（进行中）的文档对应的知识库任务，检查是否达到最大重试次数
            for (KnowledgeDocumentModel doc : docList) {
                Integer currentStatus = finalTripleStatusMap.get(doc.getId());
                // 如果是构建中状态，才查询任务表
                if (currentStatus != null && currentStatus == 1) {
                    // 查询该文档最近一次的知识库任务记录（按创建时间倒序）
                    KnowledgeTaskModel latestTask = knowledgeTaskRepository.queryLatestOneByDocId(doc.getId());
                    if (latestTask != null) {
                        // 检查是否达到最大重试次数
                        if (latestTask.getMaxRetryCnt() != null
                                && latestTask.getRetryCnt() != null
                                //&& latestTask.getMaxRetryCnt().equals(latestTask.getRetryCnt())) {
                                && latestTask.getRetryCnt() >= latestTask.getMaxRetryCnt()) {
                            // 重试次数达到上限，标记为失败
                            finalTripleStatusMap.put(doc.getId(), 10);
                            log.info("文档ID: {} 达到最大重试次数，状态从1更新为10", doc.getId());
                        }
                    }
                }
            }

            // 使用转换器组装返回结果
            List<KnowledgeGraphListItemVo> result = tripleConverter.convertToGraphListItemList(docList, finalTripleStatusMap);

            // 按 tripleStatus 排序：1(进行中) -> 2(成功) -> 10(失败) -> 其他
            result = sortByTripleStatus(result);

            return ReqResult.success(result);
        } catch (Exception e) {
            log.error("查询知识图谱列表失败", e);
            return ReqResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 生成知识图谱（单个文档，异步处理）
     */
    @Operation(summary = "生成知识图谱")
    @PostMapping("/generate")
    public ReqResult<String> generateKnowledgeGraph(
            @RequestBody KnowledgeGraphGenerateRequest request) {
        try {
            if (request == null || request.getKnowledgeId() == null) {
                return ReqResult.error("知识库ID不能为空");
            }
            if (request.getDocumentId() == null) {
                return ReqResult.error("文档ID不能为空");
            }

            Long knowledgeId = request.getKnowledgeId();
            Long documentId = request.getDocumentId();

            // 查询文档验证是否属于该知识库
            KnowledgeDocumentModel doc = knowledgeDocumentRepository.queryOneInfoById(documentId);
            if (doc == null || !knowledgeId.equals(doc.getKbId())) {
                return ReqResult.error("文档不存在或不属于该知识库");
            }

            // 检查文档智能分段状态：必须分析成功才能生成知识图谱
            // docStatus: 1-分析中, 2-分析成功, 10-分析失败
            if (doc.getDocStatus() != null) {
                if (doc.getDocStatus().getCode() == 1) {
                    return ReqResult.success("文档正在智能分段中，请等待分析完成后再生成知识图谱");
                }
                if (doc.getDocStatus().getCode() == 10) {
                    return ReqResult.error("文档智能分段失败，请重新上传或联系管理员");
                }
                if (doc.getDocStatus().getCode() != 2) {
                    return ReqResult.success("文档尚未完成智能分段，请稍后重试");
                }
            }

            // 查询文档解析状态
            DocumentParseStatusModel status = documentParseStatusRepository.queryByDocumentId(documentId);
            Integer tripleStatus = (status != null) ? status.getTripleStatus() : 0;

            // 状态检查
            if (tripleStatus == 1) {
                // 查询该文档最新的一条任务记录，判断是否超过试错次数
                KnowledgeTaskModel latestTask = knowledgeTaskRepository.queryLatestOneByDocId(documentId);
                if (latestTask != null) {
                    // 检查是否达到最大重试次数
                    if (latestTask.getMaxRetryCnt() != null
                            && latestTask.getRetryCnt() != null
                            && latestTask.getRetryCnt() >= latestTask.getMaxRetryCnt()) {
                        // 重试次数达到上限，允许重新执行
                        log.info("文档ID: {} 达到最大重试次数，允许重新执行", documentId);
                    } else {
                        // 未达到最大重试次数，仍在处理中
                        return ReqResult.success("该文档正在处理中，请稍后查看结果");
                    }
                } else {
                    // 没有任务记录，认为是在处理中
                    return ReqResult.success("该文档正在处理中，请稍后查看结果");
                }
            }

            if (tripleStatus == 2) {
                return ReqResult.success("该文档已处理完成，无需重复处理");
            }

            // 状态为失败(10)或未开始(0)，开始处理
            /*
            UserContext userContext = UserContext.builder()
                    .userId(0L)
                    .userName("知识图谱生成")
                    .build();*/
            var userDto = (UserDto) RequestContext.get().getUser();
            var tenantConfigDto = RequestContext.get().getTenantConfig();
            UserContext userContext = UserContext.builder()
                    .userId(userDto.getId())
                    .userName(userDto.getUserName())
                    .nickName(userDto.getNickName())
                    .email(userDto.getEmail())
                    .phone(userDto.getPhone())
                    .status(userDto.getStatus() == User.Status.Enabled ? 1 : -1)
                    .tenantId(userDto.getTenantId())
                    .tenantName(null)
                    .tenantConfig(tenantConfigDto)
                    .orgId(null)
                    .orgName(null)
                    .roleType(null)
                    .build();

            // 如果是重试（之前失败过），先删除 SurrealDB 中的旧数据
            if (tripleStatus != null && tripleStatus == 10) {
                log.info("【知识图谱生成】重试文档，先清理旧数据: documentId={}", documentId);
                try {
                    surrealDbService.deleteTriplesByDocumentId(documentId);
                    log.info("【知识图谱生成】清理旧数据完成: documentId={}", documentId);
                } catch (Exception e) {
                    log.warn("【知识图谱生成】清理旧数据失败（可能不存在）: documentId={}, error={}", documentId, e.getMessage());
                }
            }

            // 记录一个重试任务,用户线程池没有执行的时候,也会有对应的重试任务记录
            var autoRecordTask = AutoRecordTask.builder()
                    .docId(documentId)
                    .spaceId(doc.getSpaceId())
                    .kbId(knowledgeId)
                    .build();
            var taskId = knowledgeTaskDomainService.createNewTask(autoRecordTask, KnowledgeTaskRunTypeEnum.GRAPH,
                    userContext);

            // 立即更新状态为"进行中"，防止重复提交
            updateTripleStatus(documentId, knowledgeId, 1, taskId, userContext);

            // 异步处理
            knowledgeGraphDomainService.generateKnowledgeGraphAsync(documentId, userContext, taskId);
            //return ReqResult.success("任务已提交，请稍后查看处理结果");
            return ReqResult.success("Your task has been submitted. Please check back later for the results.");
        } catch (Exception e) {
            log.error("生成知识图谱失败: request={}", request, e);
            return ReqResult.error("生成失败: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建排序条件
     */
    private List<OrderItem> buildOrderItems(String createdOrder, String updatedOrder) {
        List<OrderItem> orderItems = new ArrayList<>();
        if (StringUtils.isNotBlank(createdOrder)) {
            if ("desc".equalsIgnoreCase(createdOrder)) {
                orderItems.add(OrderItem.desc("created"));
            } else {
                orderItems.add(OrderItem.asc("created"));
            }
        }
        if (StringUtils.isNotBlank(updatedOrder)) {
            if ("desc".equalsIgnoreCase(updatedOrder)) {
                orderItems.add(OrderItem.desc("modified"));
            } else {
                orderItems.add(OrderItem.asc("modified"));
            }
        }
        // 默认按创建时间降序
        if (orderItems.isEmpty()) {
            orderItems.add(OrderItem.desc("created"));
        }
        return orderItems;
    }

    /**
     * 按 tripleStatus 排序：1(进行中) -> 2(成功) -> 10(失败) -> 其他
     */
    private List<KnowledgeGraphListItemVo> sortByTripleStatus(List<KnowledgeGraphListItemVo> list) {
        return list.stream()
                .sorted((a, b) -> {
                    int orderA = getTripleStatusOrder(a.getTripleStatus());
                    int orderB = getTripleStatusOrder(b.getTripleStatus());
                    return orderA - orderB;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取 tripleStatus 的排序权重
     */
    private int getTripleStatusOrder(Integer tripleStatus) {
        if (tripleStatus == null) {
            return 4;
        }
        return switch (tripleStatus) {
            case 1 -> 1;  // 进行中 - 排第一
            case 2 -> 2;  // 成功 - 排第二
            case 10 -> 3; // 失败 - 排第三
            default -> 4; // 其他（包括0未开始）- 排最后
        };
    }

    /**
     * 更新文档的三元组解析状态
     */
    private void updateTripleStatus(Long documentId, Long kbId, Integer tripleStatus, Long taskId, UserContext userContext) {
        DocumentParseStatusModel existingStatus = documentParseStatusRepository.queryByDocumentId(documentId);
        DocumentParseStatusModel model = new DocumentParseStatusModel();
        model.setDocumentId(documentId);
        model.setKbId(kbId);
        model.setTripleStatus(tripleStatus);
        model.setTaskId(taskId);

        if (existingStatus == null) {
            documentParseStatusRepository.addInfo(model, userContext);
        } else {
            model.setId(existingStatus.getId());
            documentParseStatusRepository.updateInfo(model, userContext);
        }
    }

    /**
     * 生成知识图谱（单个文档，异步处理）
     */
    @Operation(summary = "批量生成知识图谱")
    @PostMapping("/generateBySyncTask")
    public ReqResult<String> generateKnowledgeGraphBySyncTask(
            @RequestBody KnowledgeGraphGenerateRequest request) {
        try {
            System.out.println("开始批量生成知识图谱");
            knowledgeGraphDomainExtService.syncTaskGraphTriple();
            System.out.println("批量生成知识图谱成功");
            return ReqResult.success("Your task has been submitted. Please check back later for the results.");
        } catch (Exception e) {
            log.error("生成知识图谱失败: request={}", request, e);
            return ReqResult.error("生成失败: " + e.getMessage());
        }
    }
}
