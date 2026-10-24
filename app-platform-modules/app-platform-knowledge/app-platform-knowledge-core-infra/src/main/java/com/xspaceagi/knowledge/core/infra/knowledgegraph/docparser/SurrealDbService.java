package com.xspaceagi.knowledge.core.infra.knowledgegraph.docparser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SurrealDB 服务类
 * 操作 main 命名空间下的知识图谱表
 *
 * 表结构:
 * - subject: 主语节点表
 * - object: 宾语节点表
 * - relation: 关系表（边表，连接 subject 和 object，关联 document）
 * - document: 文档表（知识库文档信息）
 *
 * @author system
 * @date 2025-04-13
 */
@Slf4j
@Data
@Service
@ConfigurationProperties(prefix = "surrealdb")
public class SurrealDbService {

    /**
     * 连接地址
     */
    //private String url = "http://127.0.0.1:8000";
    private String url = "http://192.168.1.34:8110";

    /**
     * 用户名
     */
    private String username = "root";

    /**
     * 密码
     */
    private String password = "root";

    /**
     * 命名空间
     */
    private String namespace = "main";

    /**
     * 数据库名称
     */
    private String database = "main";

    /**
     * 连接超时时间(毫秒)
     */
    private Integer connectTimeout = 5000;

    /**
     * 是否启用
     */
    private Boolean enabled = true;

    /**
     * 初始化日志 - 验证配置是否正确加载
     */
    @PostConstruct
    public void init() {
        log.info("【SurrealDB配置】url={}, username={}, namespace={}, database={}, enabled={}",
                url, username, namespace, database, enabled);
    }

    // ==================== 三元组模型 ====================

    /**
     * 三元组数据模型
     */
    @Data
    public static class Triple {
        /** 主语 */
        private String subject;
        /** 谓语/关系 */
        private String predicate;
        /** 宾语 */
        private String object;
        /** 知识库ID */
        private Long knowledgeId;
        /** 文档ID */
        private Long documentId;
        /** 文档名称 */
        private String documentName;
        /** 文件类型 */
        private String fileType;
        /** 分段ID */
        private Long segmentId;
        /** 分段内容 */
        private String segmentContent;
    }

    // ==================== 主语节点相关方法 ====================

    /**
     * 创建主语节点
     *
     * @param content 主语内容
     * @return 生成的 record ID
     */
    public String createSubject(String content) {
        try {
            HttpClient client = createClient();

            String sql = String.format(
                    "USE NS %s DB %s; " +
                    "CREATE subject SET content = '%s' RETURN id;",
                    namespace, database,
                    escapeSql(content)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("创建主语节点[{}] 结果: status={}", content, response.statusCode());

            return extractRecordId(response.body());
        } catch (Exception e) {
            log.error("创建主语节点失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查找或创建主语节点
     *
     * @param content 主语内容
     * @return record ID
     */
    public String findOrCreateSubject(String content) {
        try {
            HttpClient client = createClient();

            // 先查找
            String querySql = String.format(
                    "USE NS %s DB %s; " +
                    "SELECT id FROM subject WHERE content = '%s';",
                    namespace, database,
                    escapeSql(content)
            );

            HttpRequest queryRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(querySql))
                    .build();

            HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());
            String existingId = extractRecordId(queryResponse.body());

            if (existingId != null) {
                return existingId;
            }

            // 不存在则创建
            return createSubject(content);
        } catch (Exception e) {
            log.error("查找或创建主语节点失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 宾语节点相关方法 ====================

    /**
     * 创建宾语节点
     *
     * @param content 宾语内容
     * @return 生成的 record ID
     */
    public String createObject(String content) {
        try {
            HttpClient client = createClient();

            String sql = String.format(
                    "USE NS %s DB %s; " +
                    "CREATE object SET content = '%s' RETURN id;",
                    namespace, database,
                    escapeSql(content)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("创建宾语节点[{}] 结果: status={}", content, response.statusCode());

            return extractRecordId(response.body());
        } catch (Exception e) {
            log.error("创建宾语节点失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查找或创建宾语节点
     *
     * @param content 宾语内容
     * @return record ID
     */
    public String findOrCreateObject(String content) {
        try {
            HttpClient client = createClient();

            // 先查找
            String querySql = String.format(
                    "USE NS %s DB %s; " +
                    "SELECT id FROM object WHERE content = '%s';",
                    namespace, database,
                    escapeSql(content)
            );

            HttpRequest queryRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(querySql))
                    .build();

            HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());
            String existingId = extractRecordId(queryResponse.body());

            if (existingId != null) {
                return existingId;
            }

            // 不存在则创建
            return createObject(content);
        } catch (Exception e) {
            log.error("查找或创建宾语节点失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 关系相关方法 ====================

    /**
     * 创建关系（新表结构：直接存储知识库ID和文档信息）
     *
     * @param subjectContent 主语内容
     * @param predicate 谓语/关系
     * @param objectContent 宾语内容
     * @param knowledgeId 知识库ID
     * @param documentId 文档ID
     * @param documentName 文档名称
     * @param fileType 文件类型
     * @return 是否成功
     */
    public boolean createRelation(String subjectContent, String predicate, String objectContent,
                                  Long knowledgeId, Long documentId, String documentName, String fileType) {
        try {
            // 查找或创建主语节点
            String subjectId = findOrCreateSubject(subjectContent);
            if (subjectId == null) {
                log.warn("创建主语节点失败: {}", subjectContent);
                return false;
            }

            // 查找或创建宾语节点
            String objectId = findOrCreateObject(objectContent);
            if (objectId == null) {
                log.warn("创建宾语节点失败: {}", objectContent);
                return false;
            }

            // 创建关系（新表结构）
            HttpClient client = createClient();

            String sql = String.format(
                    "USE NS %s DB %s; " +
                    "CREATE relation SET in = type::record('%s'), out = type::record('%s'), predicate = '%s', " +
                    "knowledge_id = %d, document_id = %d, document_name = '%s', file_type = '%s';",
                    namespace, database,
                    subjectId,
                    objectId,
                    escapeSql(predicate),
                    knowledgeId != null ? knowledgeId : 0,
                    documentId != null ? documentId : 0,
                    escapeSql(documentName != null ? documentName : ""),
                    escapeSql(fileType != null ? fileType : "")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("创建关系 [{}, {}, {}] 结果: status={}", subjectContent, predicate, objectContent, response.statusCode());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("创建关系失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建关系（兼容旧方法，已废弃）
     *
     * @deprecated 使用新的 createRelation 方法
     */
    @Deprecated
    public boolean createRelation(String subjectContent, String predicate, String objectContent, String documentId) {
        return createRelation(subjectContent, predicate, objectContent, null, null, null, null);
    }

    /**
     * 批量保存三元组（新表结构：直接存储知识库ID和文档信息）
     * 优化版本：使用批量插入减少HTTP请求次数
     *
     * @param triples 三元组列表
     * @return 成功保存的数量
     */
    public int saveTriples(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return 0;
        }

        // 获取文档信息（所有三元组应该属于同一文档）
        Triple firstTriple = triples.get(0);
        Long knowledgeId = firstTriple.getKnowledgeId();
        Long documentId = firstTriple.getDocumentId();
        String documentName = firstTriple.getDocumentName();
        String fileType = firstTriple.getFileType();

        log.info("【批量保存三元组】开始: 总数={}, 知识库={}, 文档={}",
                triples.size(), knowledgeId, documentId);

        try {
            // 第一步：批量创建所有主语和宾语节点
            Set<String> subjects = triples.stream()
                    .map(Triple::getSubject)
                    .collect(Collectors.toSet());
            Set<String> objects = triples.stream()
                    .map(Triple::getObject)
                    .collect(Collectors.toSet());

            log.info("【批量保存三元组】准备创建节点: 主语={}, 宾语={}", subjects.size(), objects.size());

            // 批量创建主语节点
            java.util.Map<String, String> subjectIdMap = batchCreateNodes(subjects, "subject");
            // 批量创建宾语节点
            java.util.Map<String, String> objectIdMap = batchCreateNodes(objects, "object");

            log.info("【批量保存三元组】节点创建完成: 主语={}, 宾语={}",
                    subjectIdMap.size(), objectIdMap.size());

            // 第二步：批量创建关系
            int successCount = batchCreateRelations(triples, subjectIdMap, objectIdMap,
                    knowledgeId, documentId, documentName, fileType);

            log.info("【批量保存三元组】完成: 总数={}, 成功={}, 知识库={}, 文档={}",
                    triples.size(), successCount, knowledgeId, documentId);
            return successCount;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("【批量保存三元组】失败: 知识库={}, 文档={}, error={}",
                    knowledgeId, documentId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 批量创建节点（主语或宾语）
     * 优化：使用 INSERT ... RETURN id, content 直接获取插入的ID
     *
     * @param contents 节点内容集合
     * @param tableName 表名（subject 或 object）
     * @return 内容到record ID的映射
     */
    private Map<String, String> batchCreateNodes(Set<String> contents, String tableName) {
        Map<String, String> idMap = new HashMap<>();
        if (contents.isEmpty()) {
            return idMap;
        }

        try {
            HttpClient client = createClient();

            // 构建批量插入SQL，使用 RETURN 直接获取 id 和 content
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append(String.format("USE NS %s DB %s; ", namespace, database));

            // 使用 INSERT 语句批量创建，SurrealDB 会自动去重，并返回插入的记录
            sqlBuilder.append(String.format("INSERT INTO %s (content) VALUES ", tableName));
            boolean first = true;
            for (String content : contents) {
                if (!first) sqlBuilder.append(", ");
                sqlBuilder.append(String.format("('%s')", escapeSql(content)));
                first = false;
            }
            sqlBuilder.append(" RETURN id, content;");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sqlBuilder.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // 直接解析插入返回的结果获取 ID 映射
                idMap = extractContentIdMapFromInsert(response.body(), tableName);

                // 如果插入返回的 ID 不足，再查询补充（处理部分节点已存在的情况）
                if (idMap.size() < contents.size()) {
                    log.info("批量创建{}节点: 插入返回 {} 个，需要补充查询", tableName, idMap.size());
                    Map<String, String> queryMap = queryExistingNodes(contents, tableName);
                    idMap.putAll(queryMap);
                }
            }

            log.info("批量创建{}节点完成: 请求={}, 成功={}", tableName, contents.size(), idMap.size());

        } catch (Exception e) {
            e.printStackTrace();
            log.error("批量创建{}节点失败: {}", tableName, e.getMessage(), e);
            log.error(url);
        }

        return idMap;
    }

    /**
     * 查询已存在的节点ID
     */
    private Map<String, String> queryExistingNodes(Set<String> contents, String tableName) {
        Map<String, String> idMap = new HashMap<>();
        if (contents.isEmpty()) {
            return idMap;
        }

        try {
            HttpClient client = createClient();

            // 分批查询，避免SQL过长
            List<String> contentList = new ArrayList<>(contents);
            int batchSize = 100;

            for (int i = 0; i < contentList.size(); i += batchSize) {
                List<String> batch = contentList.subList(i, Math.min(i + batchSize, contentList.size()));

                String querySql = String.format(
                        "USE NS %s DB %s; SELECT id, content FROM %s WHERE content IN [%s];",
                        namespace, database, tableName,
                        batch.stream()
                                .map(c -> "'" + escapeSql(c) + "'")
                                .collect(Collectors.joining(", "))
                );

                HttpRequest queryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/sql"))
                        .header("Accept", "application/json")
                        .header("Content-Type", "text/plain")
                        .header("Authorization", "Basic " + encodeBasicAuth())
                        .POST(HttpRequest.BodyPublishers.ofString(querySql))
                        .build();

                HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());

                if (queryResponse.statusCode() == 200) {
                    Map<String, String> batchMap = extractContentIdMap(queryResponse.body(), tableName);
                    idMap.putAll(batchMap);
                }
            }

        } catch (Exception e) {
            log.error("查询已存在{}节点失败: {}", tableName, e.getMessage());
        }

        return idMap;
    }

    /**
     * 从 INSERT RETURN 响应中提取内容到ID的映射
     */
    private Map<String, String> extractContentIdMapFromInsert(String responseBody, String tableName) {
        Map<String, String> map = new HashMap<>();
        try {
            JsonElement rootElement = JsonParser.parseString(responseBody);

            // SurrealDB INSERT RETURN 响应格式:
            // [{"result": [{"id": "subject:xxx", "content": "xxx"}, ...]}]
            if (rootElement.isJsonArray()) {
                JsonArray results = rootElement.getAsJsonArray();
                for (JsonElement result : results) {
                    if (result.isJsonObject() && result.getAsJsonObject().has("result")) {
                        JsonElement resultElement = result.getAsJsonObject().get("result");

                        if (resultElement.isJsonArray()) {
                            JsonArray records = resultElement.getAsJsonArray();
                            for (JsonElement record : records) {
                                extractRecordToMap(record, map);
                            }
                        } else if (resultElement.isJsonObject()) {
                            extractRecordToMap(resultElement, map);
                        }
                    }
                }
            }

            log.debug("从INSERT提取{}节点ID映射: {} 条记录", tableName, map.size());

        } catch (Exception e) {
            log.error("从INSERT提取内容ID映射失败: {}, responseBody={}", e.getMessage(),
                    StringUtils.abbreviate(responseBody, 200));
        }
        return map;
    }

    /**
     * 批量创建关系
     *
     * @param triples 三元组列表
     * @param subjectIdMap 主语ID映射
     * @param objectIdMap 宾语ID映射
     * @param knowledgeId 知识库ID
     * @param documentId 文档ID
     * @param documentName 文档名称
     * @param fileType 文件类型
     * @return 成功创建的数量
     */
    private int batchCreateRelations(List<Triple> triples,
                                     java.util.Map<String, String> subjectIdMap,
                                     java.util.Map<String, String> objectIdMap,
                                     Long knowledgeId, Long documentId,
                                     String documentName, String fileType) {
        int successCount = 0;
        int batchSize = 50; // 每批50个关系

        for (int i = 0; i < triples.size(); i += batchSize) {
            List<Triple> batch = triples.subList(i, Math.min(i + batchSize, triples.size()));

            try {
                HttpClient client = createClient();

                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(String.format("USE NS %s DB %s; ", namespace, database));
                sqlBuilder.append("INSERT INTO relation (in, out, predicate, knowledge_id, document_id, document_name, file_type, segment_id, segment_content) VALUES ");

                boolean first = true;
                for (Triple triple : batch) {
                    String subjectId = subjectIdMap.get(triple.getSubject());
                    String objectId = objectIdMap.get(triple.getObject());

                    if (subjectId == null || objectId == null) {
                        log.warn("节点ID未找到: subject={}, object={}",
                                triple.getSubject(), triple.getObject());
                        continue;
                    }

                    if (!first) sqlBuilder.append(", ");
                    sqlBuilder.append(String.format(
                            "(type::record('%s'), type::record('%s'), '%s', %d, %d, '%s', '%s', %d, '%s')",
                            subjectId, objectId,
                            escapeSql(triple.getPredicate()),
                            triple.getKnowledgeId() != null ? triple.getKnowledgeId() : 0,
                            triple.getDocumentId() != null ? triple.getDocumentId() : 0,
                            escapeSql(triple.getDocumentName() != null ? triple.getDocumentName() : ""),
                            escapeSql(triple.getFileType() != null ? triple.getFileType() : ""),
                            triple.getSegmentId() != null ? triple.getSegmentId() : 0,
                            escapeSql(triple.getSegmentContent() != null ? triple.getSegmentContent() : "")
                    ));
                    first = false;
                }
                sqlBuilder.append(";");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/sql"))
                        .header("Accept", "application/json")
                        .header("Content-Type", "text/plain")
                        .header("Authorization", "Basic " + encodeBasicAuth())
                        .POST(HttpRequest.BodyPublishers.ofString(sqlBuilder.toString()))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    successCount += batch.size();
                    log.debug("批量创建关系成功: 批次大小={}", batch.size());
                } else {
                    log.error("批量创建关系失败: status={}, body={}",
                            response.statusCode(), response.body());
                }

            } catch (Exception e) {
                log.error("批量创建关系批次失败: 批次起始={}, 大小={}, error={}",
                        i, batch.size(), e.getMessage(), e);
            }
        }

        return successCount;
    }

    /**
     * 从响应中提取内容到ID的映射
     *
     * @param responseBody 响应体
     * @param tableName 表名
     * @return 内容到ID的映射
     */
    private Map<String, String> extractContentIdMap(String responseBody, String tableName) {
        Map<String, String> map = new HashMap<>();
        try {
            JsonElement rootElement = JsonParser.parseString(responseBody);

            // 处理 SurrealDB 的响应格式
            // 格式1: [{"result": [{...}, {...}]}]
            // 格式2: [{"result": {...}}]
            if (rootElement.isJsonArray()) {
                JsonArray results = rootElement.getAsJsonArray();
                for (JsonElement result : results) {
                    if (result.isJsonObject() && result.getAsJsonObject().has("result")) {
                        JsonElement resultElement = result.getAsJsonObject().get("result");

                        // result 可能是数组或单个对象
                        if (resultElement.isJsonArray()) {
                            JsonArray records = resultElement.getAsJsonArray();
                            for (JsonElement record : records) {
                                extractRecordToMap(record, map);
                            }
                        } else if (resultElement.isJsonObject()) {
                            extractRecordToMap(resultElement, map);
                        }
                    }
                }
            } else if (rootElement.isJsonObject()) {
                // 直接是对象的情况
                extractRecordToMap(rootElement, map);
            }

            log.debug("提取{}节点ID映射: 解析到 {} 条记录", tableName, map.size());

        } catch (Exception e) {
            log.error("提取内容ID映射失败: {}, responseBody={}", e.getMessage(),
                    StringUtils.abbreviate(responseBody, 200));
        }
        return map;
    }

    /**
     * 从记录中提取 id 和 content 到 map
     */
    private void extractRecordToMap(JsonElement record, Map<String, String> map) {
        if (!record.isJsonObject()) {
            return;
        }
        JsonObject obj = record.getAsJsonObject();
        if (obj.has("id") && obj.has("content")) {
            String id = obj.get("id").getAsString();
            String content = obj.get("content").getAsString();
            map.put(content, id);
        }
    }

    // ==================== 查询相关方法 ====================

    /**
     * 查询所有主语节点
     *
     * @return JSON 结果
     */
    public String queryAllSubjects() {
        return executeQuery("SELECT * FROM subject;");
    }

    /**
     * 查询所有宾语节点
     *
     * @return JSON 结果
     */
    public String queryAllObjects() {
        return executeQuery("SELECT * FROM object;");
    }

    /**
     * 查询所有关系
     *
     * @return JSON 结果
     */
    public String queryAllRelations() {
        return executeQuery("SELECT in.content AS subject, predicate, out.content AS object FROM relation;");
    }

    /**
     * 查询某主语的所有关系
     *
     * @param subjectContent 主语内容
     * @return JSON 结果
     */
    public String queryRelationsBySubject(String subjectContent) {
        return executeQuery(String.format(
                "SELECT in.content AS subject, predicate, out.content AS object FROM relation WHERE in.content = '%s';",
                escapeSql(subjectContent)
        ));
    }

    // ==================== 知识图谱查询方法 ====================

    /**
     * 按知识库ID查询三元组（新表结构）
     *
     * @param knowledgeId 知识库ID
     * @return JSON 结果
     */
    public String queryTriplesByKnowledgeId(Long knowledgeId) {
        return executeQuery(String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation " +
                "WHERE knowledge_id = %d;",
                knowledgeId
        ));
    }

    /**
     * 按知识库ID分页查询三元组（新表结构）
     *
     * @param knowledgeId 知识库ID
     * @param pageNum 页码（从1开始）
     * @param pageSize 每页条数
     * @return JSON 结果
     */
    public String queryTriplesByKnowledgeIdPage(Long knowledgeId, Integer pageNum, Integer pageSize) {
        int start = (pageNum - 1) * pageSize;
        return executeQuery(String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType, " +
                "segment_id AS segmentId, " +
                "segment_content AS segmentContent " +
                "FROM relation " +
                "WHERE knowledge_id = %d " +
                "LIMIT %d START %d;",
                knowledgeId, pageSize, start
        ));
    }

    /**
     * 按知识库ID查询三元组总数（新表结构）
     *
     * @param knowledgeId 知识库ID
     * @return 总数
     */
    public Long countTriplesByKnowledgeId(Long knowledgeId) {
        String result = executeQuery(String.format(
                "SELECT count() AS total FROM relation WHERE knowledge_id = %d GROUP ALL;",
                knowledgeId
        ));
        try {
            JsonArray array = JsonParser.parseString(result).getAsJsonArray();
            if (array.size() > 0) {
                JsonObject obj = array.get(0).getAsJsonObject();
                return obj.get("total").getAsLong();
            }
        } catch (Exception e) {
            log.error("查询三元组总数失败, knowledgeId={}", knowledgeId, e);
        }
        return 0L;
    }

    /**
     * 按文档ID查询三元组（新表结构）
     *
     * @param documentId 文档ID（MySQL中的主键）
     * @return JSON 结果
     */
    public String queryTriplesByDocumentId(Long documentId) {
        return executeQuery(String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation " +
                "WHERE document_id = %d;",
                documentId
        ));
    }

    /**
     * 按文档名称模糊查询三元组（新表结构）
     *
     * @param documentName 文档名称
     * @return JSON 结果
     */
    public String queryTriplesByDocumentName(String documentName) {
        return executeQuery(String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation " +
                "WHERE document_name CONTAINS '%s';",
                escapeSql(documentName)
        ));
    }

    /**
     * 查询所有三元组（新表结构）
     *
     * @return JSON 结果
     */
    public String queryAllTriplesWithDocument() {
        return executeQuery(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation;"
        );
    }

    /**
     * 按知识库ID统计（新表结构）
     *
     * @param knowledgeId 知识库ID
     * @return JSON 结果
     */
    public String statsByKnowledgeId(Long knowledgeId) {
        return executeQuery(String.format(
                "SELECT " +
                "count() AS tripleCount " +
                "FROM relation " +
                "WHERE knowledge_id = %d " +
                "GROUP ALL;",
                knowledgeId
        ));
    }

    /**
     * 按知识库ID统计谓语类型（新表结构）
     *
     * @param knowledgeId 知识库ID
     * @return JSON 结果
     */
    public String predicateStatsByKnowledgeId(Long knowledgeId) {
        return executeQuery(String.format(
                "SELECT " +
                "predicate, " +
                "count() AS count " +
                "FROM relation " +
                "WHERE knowledge_id = %d " +
                "GROUP BY predicate;",
                knowledgeId
        ));
    }

    /**
     * 查询知识库下的所有文档（新表结构：从 relation 表聚合）
     *
     * @param knowledgeId 知识库ID
     * @return JSON 结果
     */
    public String queryDocumentsByKnowledgeId(Long knowledgeId) {
        return executeQuery(String.format(
                "SELECT " +
                "document_id AS documentId, " +
                "document_name AS name, " +
                "file_type AS fileType, " +
                "count() AS tripleCount " +
                "FROM relation " +
                "WHERE knowledge_id = %d " +
                "GROUP BY document_id, document_name, file_type;",
                knowledgeId
        ));
    }

    /**
     * 删除文档的所有三元组（新表结构）
     *
     * @param documentId 文档ID（MySQL中的主键）
     * @return 是否成功
     */
    public boolean deleteTriplesByDocumentId(Long documentId) {
        try {
            HttpClient client = createClient();

            String sql = String.format(
                    "USE NS %s DB %s; " +
                    "DELETE FROM relation WHERE document_id = %d;",
                    namespace, database,
                    documentId
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("删除文档三元组[documentId={}] 结果: status={}", documentId, response.statusCode());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("删除文档三元组失败: {}", e.getMessage(), e);
            return false;
        }
    }

    // ==================== 图检索相关方法 ====================

    /**
     * 按主体模糊查询三元组（知识图谱检索）
     * 第一阶段：仅按主体关键词粗筛，后续在应用层做 predicate 相似度过滤
     * 新表结构：直接使用 knowledge_id 字段
     *
     * @param knowledgeIds   知识库ID列表
     * @param subjectKeyword 主体关键词
     * @param limit          返回数量限制
     * @return JSON 结果
     */
    public String queryTriplesBySubjectKeyword(List<Long> knowledgeIds, String subjectKeyword, Integer limit) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return "[]";
        }

        // 构建知识库ID列表
        String kbIdList = knowledgeIds.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));

        String limitClause = limit != null && limit > 0 ? " LIMIT " + limit : "";

        return executeQuery(String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation " +
                "WHERE knowledge_id IN [%s] " +
                "AND in.content CONTAINS '%s' " +
                "%s;",
                kbIdList,
                escapeSql(subjectKeyword),
                limitClause
        ));
    }

    /**
     * 按主体列表批量查询三元组
     * 用于多个三元组主体的联合查询
     * 新表结构：直接使用 knowledge_id 字段
     *
     * @param knowledgeIds    知识库ID列表
     * @param subjectKeywords 主体关键词列表
     * @return JSON 结果
     */
    public String queryTriplesBySubjectKeywords(List<Long> knowledgeIds, List<String> subjectKeywords) {
        if (knowledgeIds == null || knowledgeIds.isEmpty() || subjectKeywords == null || subjectKeywords.isEmpty()) {
            log.warn("【SurrealDB查询】参数为空，knowledgeIds={}, subjectKeywords={}", knowledgeIds, subjectKeywords);
            return "[]";
        }

        // 构建知识库ID列表
        String kbIdList = knowledgeIds.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));

        // 构建主体条件（OR 连接）
        String subjectConditions = subjectKeywords.stream()
                .map(kw -> "in.content CONTAINS '" + escapeSql(kw) + "'")
                .collect(java.util.stream.Collectors.joining(" OR "));

        String querySql = String.format(
                "SELECT " +
                "id, " +
                "in.content AS subject, " +
                "predicate, " +
                "out.content AS object, " +
                "knowledge_id AS knowledgeId, " +
                "document_id AS documentId, " +
                "document_name AS documentName, " +
                "file_type AS fileType " +
                "FROM relation " +
                "WHERE knowledge_id IN [%s] " +
                "AND (%s);",
                kbIdList,
                subjectConditions
        );

        log.info("【SurrealDB查询】生成的SQL: {}", querySql);
        log.info("【SurrealDB查询】knowledgeIds: {}, subjectKeywords: {}", knowledgeIds, subjectKeywords);

        String result = executeQuery(querySql);
        log.info("【SurrealDB查询】返回结果长度: {}", result != null ? result.length() : 0);

        return result;
    }

    /**
     * 执行查询
     */
    private String executeQuery(String querySql) {
        try {
            HttpClient client = createClient();

            String sql = String.format(
                    "USE NS %s DB %s; %s",
                    namespace, database,
                    querySql
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/sql"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Basic " + encodeBasicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            log.error("查询失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从响应中提取 record ID
     */
    private String extractRecordId(String responseBody) {
        try {
            // 响应格式: [{"result":[{"id":"subject:xxx"}],"status":"OK"}]
            int idStart = responseBody.indexOf("\"id\":\"");
            if (idStart > 0) {
                int idEnd = responseBody.indexOf("\"", idStart + 6);
                if (idEnd > idStart) {
                    return responseBody.substring(idStart + 6, idEnd);
                }
            }
        } catch (Exception e) {
            log.warn("解析record ID失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 转义SQL字符串中的特殊字符
     */
    private String escapeSql(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 创建HTTP客户端
     */
    private HttpClient createClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    /**
     * 编码Basic认证头
     */
    private String encodeBasicAuth() {
        String credentials = username + ":" + password;
        return Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            HttpClient client = createClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/health"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            log.info("SurrealDB 连接测试: {}", success ? "成功" : "失败");
            return success;
        } catch (Exception e) {
            log.error("连接测试失败: {}", e.getMessage());
            return false;
        }
    }
}