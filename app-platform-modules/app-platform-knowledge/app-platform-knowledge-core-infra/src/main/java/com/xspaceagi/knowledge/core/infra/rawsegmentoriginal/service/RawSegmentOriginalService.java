package com.xspaceagi.knowledge.core.infra.rawsegmentoriginal.service;

import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原文对照服务
 * 根据分段ID查询所属文档的全部分段（按sort_index排序）
 */
@Service
@Slf4j
public class RawSegmentOriginalService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 根据分段ID查询该文档的所有分段
     *
     * @param segmentId 分段ID
     * @param agentId 智能体ID
     * @return 分页结果；权限拒绝时返回 permissionDenied=true 的空结果（不抛异常，前端据 data.permissionDenied 提示）
     */
    public Map<String, Object> listOriginalTextBySegmentId(Long segmentId, Long agentId) {
        try {
            // 两级权限校验
            RequestContext<?> context = RequestContext.get();
            if (context == null) {
                throw BizException.of(BizExceptionCodeEnum.userNotLoggedIn);
            }
            Long userId = context.getUserId();
            Long tenantId = context.getTenantId();

            // 第一级：用户是否有权访问该智能体
            // 第二级：分段所属知识库是否在智能体绑定的知识库列表中
            // 校验失败：返回 permissionDenied=true（不抛异常，前端据 data.permissionDenied 提示）
            if (!checkAgentAccessible(agentId, userId, tenantId)
                    || !checkSegmentInAgentKb(agentId, segmentId)) {
                return buildPermissionDeniedResult();
            }

            // 1. 先查询该分段所属的文档ID
            String docQuerySql = "SELECT doc_id FROM knowledge_raw_segment WHERE id = ? AND yn = 1";
            Long docId = null;

            try {
                docId = jdbcTemplate.queryForObject(docQuerySql, Long.class, segmentId);
            } catch (Exception e) {
                log.warn("分段ID {} 不存在或已被删除", segmentId);
                return buildEmptyPage();
            }

            if (docId == null) {
                log.warn("分段ID {} 对应的文档ID为空", segmentId);
                return buildEmptyPage();
            }

            // 2. 根据文档ID查询文档名称
            String docName = queryDocumentName(docId);

            // 3. 查询该文档的所有分段
            String segmentsQuerySql =
                    "SELECT id, doc_id, raw_txt, kb_id, sort_index, space_id, " +
                            "created, creator_id, creator_name, modified, modified_id, modified_name " +
                            "FROM knowledge_raw_segment " +
                            "WHERE doc_id = ? AND yn = 1 " +
                            "ORDER BY sort_index ASC";

            List<Map<String, Object>> records = jdbcTemplate.queryForList(segmentsQuerySql, docId);

            // 4. 转换字段名为驼峰命名
            List<Map<String, Object>> camelCaseRecords = records.stream()
                    .map(this::convertToCamelCase)
                    .toList();

            // 5. 构建分页结果（附带文档名称）
            return buildPageResult(camelCaseRecords, docName);

        } catch (BizException e) {
            // 业务异常直接抛出
            throw e;
        } catch (Exception e) {
            log.error("查询原文对照失败，segmentId: {}", segmentId, e);
            throw new RuntimeException("查询原文对照失败: " + e.getMessage());
        }
    }

    /**
     * 构建空分页结果
     */
    private Map<String, Object> buildEmptyPage() {
        Map<String, Object> result = new HashMap<>();
        result.put("records", List.of());
        result.put("total", 0);
        result.put("size", 10);
        result.put("current", 1);
        result.put("pages", 0);
        return result;
    }

    /**
     * 构建权限拒绝结果（不抛异常，前端据 permissionDenied 标记用 i18n 提示）
     */
    private Map<String, Object> buildPermissionDeniedResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("permissionDenied", true);
        result.put("records", List.of());
        result.put("total", 0);
        result.put("size", 0);
        result.put("current", 1);
        result.put("pages", 0);
        return result;
    }

    /**
     * 构建分页结果
     *
     * @param records 分段记录列表
     * @param docName 文档名称（来自 knowledge_document 表的 name 字段）
     */
    private Map<String, Object> buildPageResult(List<Map<String, Object>> records, String docName) {
        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", records.size());
        result.put("size", records.size());
        result.put("current", 1);
        result.put("pages", records.size() > 0 ? 1 : 0);
        result.put("docname", docName);
        return result;
    }

    /**
     * 根据文档ID查询文档名称
     *
     * @param docId 文档ID
     * @return 文档名称；查询不到时返回空字符串
     */
    private String queryDocumentName(Long docId) {
        String docNameSql = "SELECT name FROM knowledge_document WHERE id = ? AND yn = 1";
        try {
            String docName = jdbcTemplate.queryForObject(docNameSql, String.class, docId);
            return docName != null ? docName : "";
        } catch (EmptyResultDataAccessException e) {
            log.warn("文档ID {} 不存在或已被删除，docname 返回空", docId);
            return "";
        }
    }

    /**
     * 将Map的键从下划线命名转换为驼峰命名
     */
    private Map<String, Object> convertToCamelCase(Map<String, Object> originalMap) {
        Map<String, Object> camelCaseMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
            String camelCaseKey = toCamelCase(entry.getKey());
            camelCaseMap.put(camelCaseKey, entry.getValue());
        }

        return camelCaseMap;
    }

    /**
     * 下划线转驼峰命名
     */
    private String toCamelCase(String underscore) {
        if (underscore == null || underscore.isEmpty()) {
            return underscore;
        }

        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;

        for (int i = 0; i < underscore.length(); i++) {
            char c = underscore.charAt(i);
            if (c == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    result.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            }
        }

        if (result.length() > 0 && Character.isUpperCase(result.charAt(0))) {
            result.setCharAt(0, Character.toLowerCase(result.charAt(0)));
        }

        return result.toString();
    }

    /**
     * 第一级校验：用户是否有权访问该智能体（创建者或同空间成员）
     *
     * @return true=有权；false=无权
     */
    private boolean checkAgentAccessible(Long agentId, Long userId, Long tenantId) {
        String checkAgentSql =
                "SELECT COUNT(*) FROM agent_config " +
                        "WHERE id = ? " +
                        "  AND _tenant_id = ? " +
                        "  AND yn = 0 " +
                        "  AND (creator_id = ? OR space_id IN (" +
                        "      SELECT space_id FROM space_user WHERE user_id = ?" +
                        "  ))";

        Integer count = jdbcTemplate.queryForObject(checkAgentSql, Integer.class,
                agentId, tenantId, userId, userId);

        return count != null && count > 0;
    }

    /**
     * 第二级校验：分段所属知识库是否在智能体绑定的知识库列表中
     *
     * @return true=在绑定范围内；false=不在（含分段不存在）
     */
    private boolean checkSegmentInAgentKb(Long agentId, Long segmentId) {
        String getKbSql = "SELECT kb_id FROM knowledge_raw_segment WHERE id = ? AND yn = 1";

        Long kbId;
        try {
            kbId = jdbcTemplate.queryForObject(getKbSql, Long.class, segmentId);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }

        if (kbId == null) {
            return false;
        }

        String checkKbSql =
                "SELECT COUNT(*) FROM agent_component_config " +
                        "WHERE agent_id = ? " +
                        "  AND type = 'Knowledge' " +
                        "  AND target_id = ?";

        Integer count = jdbcTemplate.queryForObject(checkKbSql, Integer.class, agentId, kbId);

        return count != null && count > 0;
    }
}
