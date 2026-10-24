package com.xspaceagi.memory.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hankcs.hanlp.HanLP;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.memory.app.service.MemoryApplicationService;
import com.xspaceagi.memory.app.service.MemoryUnitService;
import com.xspaceagi.memory.app.service.MemoryUnitTagService;
import com.xspaceagi.memory.app.service.dto.*;
import com.xspaceagi.memory.infra.dao.entity.MemoryUnit;
import com.xspaceagi.memory.infra.dao.entity.MemoryUnitTag;
import com.xspaceagi.memory.sdk.dto.*;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MemoryApplicationServiceImpl implements MemoryApplicationService {

    // JSON key validation pattern: only alphanumeric, underscore, and dot allowed
    private static final Pattern JSON_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    // P0: 无效记忆值黑名单(占位/无意义值),命中则剔除该 key
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "未知", "无", "n/a", "na", "null", "none", "待补充", "具体信息待补充",
            "已过期", "需处理", "-", "待定", "暂无", "待确认");

    // P0: 环境路径/噪声特征(绝对路径前缀),防止本地路径误当记忆值入库
    private static final Pattern ENV_PATH_PATTERN = Pattern.compile("^(?:/[\\w.\\-]+(?:/[\\w.\\-]+)+|[A-Za-z]:[\\\\/])");

    @Autowired
    private MemoryUnitTagService memoryUnitTagService;

    /**
     * Validate JSON keys to prevent SQL injection
     * Only allows alphanumeric characters, underscore, and dot
     */
    private void validateJsonKeys(Map<String, String> jsonKeyValues) {
        if (jsonKeyValues == null || jsonKeyValues.isEmpty()) {
            return;
        }
        for (String key : jsonKeyValues.keySet()) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("JSON key cannot be null or empty");
            }
            if (!JSON_KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid JSON key: " + key +
                        ". Only alphanumeric characters, underscore, and dot are allowed");
            }
        }
    }

    /**
     * P0: 判断提取的条目是否需要跳过
     * - 模型自评 shouldSave=false(无记忆可存)
     * - 缺一级/二级分类
     */
    private boolean shouldSkipExtractedItem(MemoryExtractItemDto item) {
        if (item.getShouldSave() != null && !item.getShouldSave()) {
            return true;
        }
        return StringUtils.isBlank(item.getCategory()) || StringUtils.isBlank(item.getSubCategory());
    }

    /**
     * P0: 剔除 keyValues 中的无效值(占位值、环境路径噪声)
     * 剔除后 keyValues 为空则由调用方跳过整条
     */
    private void filterInvalidKeyValues(Map<String, String> keyValues) {
        if (keyValues == null || keyValues.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, String>> iterator = keyValues.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            String value = entry.getValue();
            if (StringUtils.isBlank(value)
                    || PLACEHOLDER_VALUES.contains(value.trim().toLowerCase())
                    || ENV_PATH_PATTERN.matcher(value).find()) {
                iterator.remove();
            }
        }
    }

    /**
     * P0: 确定性去重——与已有记忆 keyValues 完全一致则视为重复,直接跳过合并 LLM
     */
    private boolean hasExactDuplicate(List<MemoryUnitDTO> existingMemories, Map<String, String> keyValues) {
        if (CollectionUtils.isEmpty(existingMemories) || keyValues == null) {
            return false;
        }
        for (MemoryUnitDTO memoryUnitDTO : existingMemories) {
            JSONObject jsonObject = JSON.parseObject(memoryUnitDTO.getContentJson());
            if (jsonObject == null) {
                continue;
            }
            Object kv = jsonObject.get("keyValues");
            if (!(kv instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> existingKeyValues = (Map<String, Object>) kv;
            if (existingKeyValues.size() != keyValues.size()) {
                continue;
            }
            boolean allEqual = keyValues.entrySet().stream().allMatch(entry ->
                    existingKeyValues.containsKey(entry.getKey())
                            && Objects.equals(String.valueOf(existingKeyValues.get(entry.getKey())), entry.getValue()));
            if (allEqual) {
                return true;
            }
        }
        return false;
    }

    private static final String MEMORY_EXTRACT_PROMPT = """
            你是一个记忆提取专家，分析用户输入，判断用户意图，提取需要记录的记忆。
            
            ## 用户输入
            {{user_input}}
            
            ## 当前对话上下文
            {{conversation_context}}
            
            ## 完整分类体系
            
            ### 一级分类
            - profile: 用户画像
            - auth: 认证信息
            - social: 社交媒体
            - project: 项目信息
            - preference: 用户偏好
            - task: 任务待办
            - note: 笔记备忘
            - idea: 创意想法
            - event: 事件日程
            - document: 文档资料
            - code: 代码技术
            - other: 其他类型
            
            ### 二级分类及可用键名
            
            #### profile 子分类及键名
            - personal: name, age, gender, birthday, avatar
            - professional: company, position, department, title, employee_id
            - location: city, address, timezone, country, coordinates
            - contact: phone, email, wechat, telegram, slack
            - identity: id_card, passport, license, ssn, tax_id
            - account: account_id, phone, email, platform
            
            #### auth 子分类及键名
            - website: url, username, password, login_method
            - service: service_name, api_key, app_id, app_secret, endpoint
            - api_key: key, secret, permissions, expiry_date
            - ssh_key: private_key, public_key, host, username, port
            - oauth_token: access_token, refresh_token, token_type, expires_in
            - database: host, port, username, password, database, connection_string
            
            #### social 子分类及键名
            - wechat: account_id, nickname, contacts, groups
            - twitter: username, handle, followers, following
            - linkedin: profile_url, connections, headline
            - contacts: name, relation, contact_info, notes
            - groups: group_name, members, topic, platform
            
            #### project 子分类及键名
            - ongoing: name, description, progress, start_date, owner
            - completed: name, completion_date, summary, outcome
            - planning: name, timeline, requirements, budget
            - milestone: name, deadline, status, dependencies
            - task: project_id, title, assignee, deadline, status
            
            #### preference 子分类及键名
            - travel: transport, airline, hotel_type, travel_style
            - food: cuisine, restrictions, favorites, dislikes
            - lifestyle: sleep_time, exercise, hobbies, daily_routine
            - entertainment: music, movies, games, sports
            - work: working_hours, tools, environment, work_style
            - general: general_preference, preference_name, value, notes
            
            #### task 子分类及键名
            - daily: title, due_date, priority, description
            - weekly: title, week, status, category
            - project: project_id, title, assignee, status
            - urgent: title, deadline, priority, impact
            - recurring: title, frequency, next_due, pattern
            
            #### note 子分类及键名
            - meeting: date, participants, summary, action_items
            - lecture: topic, key_points, references, instructor
            - research: subject, findings, sources, methodology
            - personal: topic, content, tags, created_at
            
            #### idea 子分类及键名
            - product: name, description, features, target_users
            - feature: component, enhancement, benefit, complexity
            - improvement: area, problem, solution, priority
            - innovation: concept, potential, challenges, feasibility
            
            #### event 子分类及键名
            - meeting: title, time, location, attendees, agenda
            - appointment: title, time, location, purpose
            - reminder: title, time, repeat, notification_method
            - deadline: task, due_date, priority, consequences
            
            #### document 子分类及键名
            - contract: title, parties, effective_date, expiry_date
            - invoice: number, amount, due_date, vendor, status
            - report: title, date, summary, author
            - reference: title, author, url, tags, category
            
            #### code 子分类及键名
            - snippet: language, description, code, filename
            - config: service, environment, settings, format
            - api_doc: endpoint, method, parameters, response_format
            - deployment: environment, version, changes, deploy_date
            
            ## 任务
            1. 判断用户输入是否包含需要记忆的信息
            2. 识别信息的分类（一级）和子分类（二级），必须从上述列表中精确选择
            3. 提取关键值和对应的键名，必须使用上述列出的可用键名
            4. 判断是否为敏感信息（密码、密钥等）
            5. 区分指令与事实：用户说“帮我记住X / 记到记忆里 / 这是我的任务”时，提取的是 X 本身的内容，不要存储这句话本身
            6. 不要将 AI 助手自身的名称、身份描述（如“我是XX助手”）识别为用户画像
            7. 时间类字段一律给出具体日期，无法确定时标为 N/A，不要存“已过期3天”这类相对表述
            """;

    private static final String MEMORY_QUERY_PROMPT = """
            你是一个记忆查询分析专家，分析用户问题，匹配一二级分类、关键词以及时间范围。
            
            ## 用户输入
            {{user_input}}
            
            ## 当前对话上下文
            {{conversation_context}}
            
            ## 完整分类体系
            
            ### 一级分类
            - profile: 用户画像
            - auth: 认证信息
            - social: 社交媒体
            - project: 项目信息
            - preference: 用户偏好
            - task: 任务待办
            - note: 笔记备忘
            - idea: 创意想法
            - event: 事件日程
            - document: 文档资料
            - code: 代码技术
            - other: 其他类型
            
            ### 二级分类及可用键名
            
            #### profile 子分类及键名
            - personal: name, age, gender, birthday, avatar
            - professional: company, position, department, title, employee_id
            - location: city, address, timezone, country, coordinates
            - contact: phone, email, wechat, telegram, slack
            - identity: id_card, passport, license, ssn, tax_id
            - account: account_id, phone, email, platform
            
            #### auth 子分类及键名
            - website: url, username, password, login_method
            - service: service_name, api_key, app_id, app_secret, endpoint
            - api_key: key, secret, permissions, expiry_date
            - ssh_key: private_key, public_key, host, username, port
            - oauth_token: access_token, refresh_token, token_type, expires_in
            - database: host, port, username, password, database, connection_string
            
            #### social 子分类及键名
            - wechat: account_id, nickname, contacts, groups
            - twitter: username, handle, followers, following
            - linkedin: profile_url, connections, headline
            - contacts: name, relation, contact_info, notes
            - groups: group_name, members, topic, platform
            
            #### project 子分类及键名
            - ongoing: name, description, progress, start_date, owner
            - completed: name, completion_date, summary, outcome
            - planning: name, timeline, requirements, budget
            - milestone: name, deadline, status, dependencies
            - task: project_id, title, assignee, deadline, status
            
            #### preference 子分类及键名
            - travel: transport, airline, hotel_type, travel_style
            - food: cuisine, restrictions, favorites, dislikes
            - lifestyle: sleep_time, exercise, hobbies, daily_routine
            - entertainment: music, movies, games, sports
            - work: working_hours, tools, environment, work_style
            - general: general_preference, preference_name, value, notes
            
            #### task 子分类及键名
            - daily: title, due_date, priority, description
            - weekly: title, week, status, category
            - project: project_id, title, assignee, status
            - urgent: title, deadline, priority, impact
            - recurring: title, frequency, next_due, pattern
            
            #### note 子分类及键名
            - meeting: date, participants, summary, action_items
            - lecture: topic, key_points, references, instructor
            - research: subject, findings, sources, methodology
            - personal: topic, content, tags, created_at
            
            #### idea 子分类及键名
            - product: name, description, features, target_users
            - feature: component, enhancement, benefit, complexity
            - improvement: area, problem, solution, priority
            - innovation: concept, potential, challenges, feasibility
            
            #### event 子分类及键名
            - meeting: title, time, location, attendees, agenda
            - appointment: title, time, location, purpose
            - reminder: title, time, repeat, notification_method
            - deadline: task, due_date, priority, consequences
            
            #### document 子分类及键名
            - contract: title, parties, effective_date, expiry_date
            - invoice: number, amount, due_date, vendor, status
            - report: title, date, summary, author
            - reference: title, author, url, tags, category
            
            #### code 子分类及键名
            - snippet: language, description, code, filename
            - config: service, environment, settings, format
            - api_doc: endpoint, method, parameters, response_format
            - deployment: environment, version, changes, deploy_date
            
            ## 任务
            1. 分析用户问题的意图
            2. 识别需要查询的记忆分类（从一级分类中精确选择）
            3. 如果问题匹配某个二级分类，也识别出来
            4. 提取标签关键词
            5. 判断是否需要约定时间范围
            """;


    private static final String MEMORY_MERGE_PROMPT = """
            你是一个记忆合并判断专家，判断新记忆是否应该更新现有记忆。
            
            ## 现有记忆
            {{existing_memory}}
            
            ## 新记忆内容
            {{new_content}}
            
            ## 任务
            1. 识别新旧记忆的关系（重复、更新、相关、独立）
            2. 如果是更新，判断需要更新的字段（keyValues中的字段）
            3. 计算相似度评分
            
            ## 核心规则：事实变更必须更新旧记忆，禁止新建冲突记忆
            - 当新记忆与某条现有记忆描述同一事实、且表达了用户的最新状态（偏好改变，如"以前喜欢打游戏，现在不喜欢了"；手机号/公司/职位等属性更新）时，必须更新该现有记忆：
              在 updateMemoryKeyValues 中返回该记忆的 memoryId 与更新后的完整 keyValues，绝不允许新建一条与之矛盾的新记忆
            - updateMemoryKeyValues.keyValues 必须返回该记忆【更新后应保留的全部字段】：被新事实推翻的旧字段用新值覆盖或直接移除（不要带上），未变化的字段照常保留
            - 现有记忆已被新事实完全取代、没有任何保留价值时，在 deleteMemoryIds 中返回该记忆的 memoryId（软删）
            - 只有新记忆与任何现有记忆都无关、且值得保存时，才放入 insertMemories
            - 由于用户每个项目的工作目录都不一样，所以关于工作目录相关的内容不要记录
            
            ## 字段说明
            - memoryId 记忆ID，用于更新现有记忆
            - category 一级记忆分类
            - subCategory 二级记忆分类
            - keyValues 一条记忆中对应的键值对（更新时为该记忆更新后的完整字段集）
            - created 已有记忆的创建时间
            - deleteMemoryIds 需要软删的现有记忆ID列表（整体被新事实取代时返回）
            """;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private MemoryUnitService memoryUnitService;

    @Override
    public List<MemoryExtractItemDto> createMemory(MemoryMetaData memoryData) {
        boolean hasRequestContext = RequestContext.get() != null;
        try {
            if (!hasRequestContext) {
                RequestContext<Object> requestContext = RequestContext.builder()
                        .tenantId(memoryData.getTenantId())
                        .userId(memoryData.getUserId())
                        .build();
                RequestContext.set(requestContext);
            }
            return createMemory0(memoryData);
        } finally {
            if (!hasRequestContext) {
                RequestContext.remove();
            }
        }
    }

    public List<MemoryExtractItemDto> createMemory0(MemoryMetaData memoryData) {
        if (StringUtils.isBlank(memoryData.getContext())) {
            return Collections.emptyList();
        }
        String userPrompt = MEMORY_EXTRACT_PROMPT.replace("{{user_input}}", memoryData.getUserInput()).replace("{{conversation_context}}", memoryData.getContext());
        MemoryExtractDto memoryExtractResult = iModelRpcService.call("当前时间: " + (new Date()), userPrompt, new ParameterizedTypeReference<MemoryExtractDto>() {
        });
        log.debug("memoryExtractResult: {}", memoryExtractResult);
        if (memoryExtractResult != null && memoryExtractResult.getItems() != null) {
            // Validate JSON keys to prevent SQL injection
            for (MemoryExtractItemDto memoryExtractDto : memoryExtractResult.getItems()) {
                Map<String, String> keyValues = memoryExtractDto.toMap();
                validateJsonKeys(keyValues);
                // P0: 过滤无效条目(shouldSave=false/缺分类)与无效值(占位值/环境路径)
                if (shouldSkipExtractedItem(memoryExtractDto)) {
                    continue;
                }
                filterInvalidKeyValues(keyValues);
                if (keyValues.isEmpty()) {
                    continue;
                }
                // 验证重复性
                List<MemoryUnitDTO> byCategoryAndJsonKeyValues = TenantFunctions.callWithIgnoreCheck(() -> memoryUnitService.findByUserIdAndCategory(memoryData.getUserId(), memoryData.getAgentId(), memoryExtractDto.getCategory(), memoryExtractDto.getSubCategory()));
                // P0: 确定性去重——keyValues 与已有记忆全等则跳过,不进合并 LLM
                if (hasExactDuplicate(byCategoryAndJsonKeyValues, keyValues)) {
                    continue;
                }
                if (byCategoryAndJsonKeyValues.isEmpty()) {
                    MemoryUnitCreateDTO memoryUnitCreateDTO = new MemoryUnitCreateDTO();
                    memoryUnitCreateDTO.setCategory(memoryExtractDto.getCategory());
                    memoryUnitCreateDTO.setSubCategory(memoryExtractDto.getSubCategory());
                    memoryUnitCreateDTO.setKeyValues(memoryExtractDto.toMap());
                    memoryUnitCreateDTO.setTags(memoryExtractDto.getTags());
                    memoryUnitCreateDTO.setIsSensitive(memoryExtractDto.getIsSensitive());
                    memoryUnitCreateDTO.setUserId(memoryData.getUserId());
                    memoryUnitCreateDTO.setAgentId(memoryData.getAgentId());
                    memoryUnitCreateDTO.setTenantId(memoryData.getTenantId());
                    memoryUnitService.create(memoryUnitCreateDTO);
                    continue;
                }
                List<MemoryUnitMergeInfo> memoryUnitMergeInfos = byCategoryAndJsonKeyValues.stream().map(memoryUnitDTO -> {
                    MemoryUnitMergeInfo memoryUnitMergeInfo = new MemoryUnitMergeInfo();
                    memoryUnitMergeInfo.setMemoryId(memoryUnitDTO.getId());
                    memoryUnitMergeInfo.setCategory(memoryUnitDTO.getCategory());
                    memoryUnitMergeInfo.setSubCategory(memoryUnitDTO.getSubCategory());
                    JSONObject jsonObject = JSON.parseObject(memoryUnitDTO.getContentJson());
                    if (jsonObject != null) {
                        memoryUnitMergeInfo.setKeyValues(jsonObject.getObject("keyValues", Map.class));
                    }
                    memoryUnitMergeInfo.setCreated(memoryUnitDTO.getCreated());
                    return memoryUnitMergeInfo;
                }).toList();
                String mergePrompt = MEMORY_MERGE_PROMPT.replace("{{existing_memory}}", JSON.toJSONString(memoryUnitMergeInfos)).replace("{{new_content}}", JSON.toJSONString(memoryExtractDto));
                MemoryMergeResultDto memoryMergeResultDto = iModelRpcService.call("当前时间: " + (new Date()), mergePrompt, new ParameterizedTypeReference<MemoryMergeResultDto>() {
                });
                log.debug("memoryMergeResultDto: {}", memoryMergeResultDto);
                if (memoryMergeResultDto != null) {
                    List<MemoryMergeResultDto.MergeUnit> insertMemories = memoryMergeResultDto.getInsertMemories();
                    if (CollectionUtils.isNotEmpty(insertMemories)) {
                        for (MemoryMergeResultDto.MergeUnit insertMemory : insertMemories) {
                            insertMemory(memoryData.getTenantId(), memoryData.getUserId(), memoryData.getAgentId(), insertMemory.getCategory(), insertMemory.getSubCategory(), insertMemory.getTags(), insertMemory.getKeyValues(), insertMemory.getIsSensitive());
                        }
                    }
                    List<MemoryMergeResultDto.MergeUnitWithID> updateMemoryKeyValues = memoryMergeResultDto.getUpdateMemoryKeyValues();
                    if (CollectionUtils.isNotEmpty(updateMemoryKeyValues)) {
                        for (MemoryMergeResultDto.MergeUnitWithID updateMemoryKeyValue : updateMemoryKeyValues) {
                            if (updateMemoryKeyValue.getMemoryId() == null
                                    || updateMemoryKeyValue.getKeyValues() == null
                                    || updateMemoryKeyValue.getKeyValues().isEmpty()) {
                                continue;
                            }
                            // P0: 敏感标记取 merge 结果自带的判断,而非外层 item 的
                            updateMemory(memoryData.getUserId(), updateMemoryKeyValue.getMemoryId(), updateMemoryKeyValue.getTags(), updateMemoryKeyValue.getKeyValues(), updateMemoryKeyValue.getIsSensitive());
                        }
                    }
                    List<Long> deleteMemoryIds = memoryMergeResultDto.getDeleteMemoryIds();
                    if (CollectionUtils.isNotEmpty(deleteMemoryIds)) {
                        // 旧记忆已被新事实完全取代、无保留价值：软删（status=deleted），避免冲突记忆并存
                        deleteMemories(memoryData.getTenantId(), memoryData.getUserId(), deleteMemoryIds);
                    }
                }
            }
        }
        return memoryExtractResult != null && memoryExtractResult.getItems() != null ? memoryExtractResult.getItems() : Collections.emptyList();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveMemories(Long tenantId, Long userId, Long agentId, MemorySaveDto memorySaveDto) {
        if (memorySaveDto.getInsertMemories() != null) {
            for (MemorySaveDto.InsertUnit insertUnit : memorySaveDto.getInsertMemories()) {
                insertMemory(tenantId, userId, agentId, insertUnit.getCategory(), insertUnit.getSubCategory(), insertUnit.getTags(), insertUnit.getKeyValues(), insertUnit.getIsSensitive());
            }
        }

        if (memorySaveDto.getUpdateMemoryKeyValues() != null) {
            for (MemorySaveDto.UpdateUnitWithID updateUnitWithID : memorySaveDto.getUpdateMemoryKeyValues()) {
                if (updateUnitWithID.getMemoryId() == null || updateUnitWithID.getKeyValues() == null) {
                    continue;
                }
                updateMemory(userId, updateUnitWithID.getMemoryId(), updateUnitWithID.getTags(), updateUnitWithID.getKeyValues(), updateUnitWithID.getIsSensitive());
            }
        }
    }

    private void insertMemory(Long tenantId, Long userId, Long agentId, String category, String subCategory, List<String> tags, Map<String, String> keyValues, Boolean isSensitive) {
        if (keyValues == null || keyValues.isEmpty() || category == null || subCategory == null) {
            return;
        }
        MemoryUnitCreateDTO memoryUnitCreateDTO = new MemoryUnitCreateDTO();
        memoryUnitCreateDTO.setCategory(category);
        memoryUnitCreateDTO.setSubCategory(subCategory);
        memoryUnitCreateDTO.setKeyValues(keyValues);
        memoryUnitCreateDTO.setTags(tags);
        memoryUnitCreateDTO.setIsSensitive(isSensitive);
        memoryUnitCreateDTO.setUserId(userId);
        memoryUnitCreateDTO.setAgentId(agentId);
        memoryUnitCreateDTO.setTenantId(tenantId);
        memoryUnitService.create(memoryUnitCreateDTO);
    }

    private void updateMemory(Long userId, Long memoryId, List<String> updateTags, Map<String, String> keyValues, Boolean isSensitive) {
        if (keyValues == null || keyValues.isEmpty()) {
            return;
        }
        MemoryUnitDTO memoryUnitDTO = memoryUnitService.getById(memoryId);
        if (memoryUnitDTO != null && memoryUnitDTO.getUserId().equals(userId)) {
            JSONObject jsonObject = JSON.parseObject(memoryUnitDTO.getContentJson());
            JSONObject oldKeyValues = jsonObject.getJSONObject("keyValues");
            if (oldKeyValues == null) {
                oldKeyValues = new JSONObject();
            }
            List<String> newTags = new ArrayList<>();
            JSONArray existingTags = jsonObject.getJSONArray("tags");
            final JSONArray targetTags;
            if (existingTags == null) {
                // 旧记忆无 tags 时新建空数组，保证合并返回的新 tags 也能落库 + 建标签关系
                // （原 isNotEmpty(tags) 条件导致旧记忆无 tags 时新 tags 整体丢失，selectWithJoinTags 检索不到）
                targetTags = new JSONArray();
                jsonObject.put("tags", targetTags);
            } else {
                targetTags = existingTags;
            }
            if (CollectionUtils.isNotEmpty(updateTags)) {
                updateTags.forEach(tag -> {
                    if (!targetTags.contains(tag)) {
                        newTags.add(tag);
                        targetTags.add(tag);
                    }
                });
            }
            // 整体替换：keyValues 是 LLM 返回的"更新后完整字段集"，被新事实推翻的旧字段自然移除
            // （对齐 DTO 注释"更新（替换原有内容）"语义；原 putAll 增量合并会残留被推翻的旧值）
            oldKeyValues.clear();
            oldKeyValues.putAll(keyValues);
            // P0: 敏感标记只升不降——已有记忆已是敏感则保持敏感,避免敏感信息在合并时被降级
            Boolean finalSensitive = Boolean.TRUE.equals(memoryUnitDTO.getIsSensitive())
                    ? Boolean.TRUE
                    : Boolean.TRUE.equals(isSensitive);
            MemoryUnitUpdateDTO updateDTO = new MemoryUnitUpdateDTO();
            updateDTO.setId(memoryUnitDTO.getId());
            updateDTO.setUserId(userId);
            updateDTO.setContentJson(jsonObject.toJSONString());
            updateDTO.setTags(newTags);
            updateDTO.setIsSensitive(finalSensitive);
            memoryUnitService.update(updateDTO);
        }
    }

    @Override
    public void deleteMemories(Long tenantId, Long userId, List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        LambdaUpdateWrapper<MemoryUnit> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(MemoryUnit::getUserId, userId);
        updateWrapper.eq(MemoryUnit::getTenantId, tenantId);
        updateWrapper.in(MemoryUnit::getId, memoryIds);
        updateWrapper.set(MemoryUnit::getStatus, "deleted");
        memoryUnitService.update(updateWrapper);
        LambdaUpdateWrapper<MemoryUnitTag> memoryUnitTagLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        memoryUnitTagLambdaUpdateWrapper.in(MemoryUnitTag::getMemoryId, memoryIds);
        memoryUnitTagService.remove(memoryUnitTagLambdaUpdateWrapper);
    }

    @Override
    public List<MemoryUnitDTO> searchMemories(Long tenantId, Long userId, Long agentId, String inputMessage, String context, boolean justKeywordSearch) {
        boolean hasRequestContext = RequestContext.get() != null;
        try {
            if (!hasRequestContext) {
                RequestContext<Object> requestContext = RequestContext.builder()
                        .tenantId(tenantId)
                        .userId(userId)
                        .build();
                RequestContext.set(requestContext);
            }
            return searchMemories0(tenantId, userId, agentId, inputMessage, context, justKeywordSearch);
        } finally {
            if (!hasRequestContext) {
                RequestContext.remove();
            }
        }
    }

    private List<MemoryUnitDTO> searchMemories0(Long tenantId, Long userId, Long agentId, String inputMessage, String context, boolean justKeywordSearch) {
        try {
            MemoryQueryExtractDto memoryQueryExtractDto = null;
            boolean fallbackQuery = false;
            if (!justKeywordSearch) {
                try {
                    String userPrompt = MEMORY_QUERY_PROMPT.replace("{{user_input}}", inputMessage).replace("{{conversation_context}}", context);
                    memoryQueryExtractDto = iModelRpcService.call("当前时间:" + (new Date()), userPrompt, new ParameterizedTypeReference<MemoryQueryExtractDto>() {
                    });
                    if (memoryQueryExtractDto.getShouldSearch() != null && !memoryQueryExtractDto.getShouldSearch()) {
                        return List.of();
                    }
                } catch (Exception ignored) {
                }
            }
            if (memoryQueryExtractDto == null) {
                // 兜底：不限制类别（全类别按 modified 倒序 + tags 检索），避免非 profile 记忆
                // （project/task/code/event 等）在对话注入时不可达；queryType=all 跨全时段
                // （parseTimeRange 默认"最近7天"窗口会把长期未更新的记忆过滤掉）；limit=20 放大注入量
                memoryQueryExtractDto = new MemoryQueryExtractDto();
                memoryQueryExtractDto.setCategories(List.of("profile", "social", "preference"));
                memoryQueryExtractDto.setSubCategories(List.of("personal", "professional", "location", "contact",
                        "identity", "account", "wechat", "twitter", "linkedin", "contacts", "groups", "travel", "food", "lifestyle", "entertainment"));
                // queryType=null 会被 parseTimeRange 兜成"最近7天"；"all" 返回空时间范围，跳过 between 过滤；
                // timeRange 自定义范围也一并清掉，避免 parseTimeRange 优先走自定义范围
                memoryQueryExtractDto.setQueryType("all");
                memoryQueryExtractDto.setTimeRange(null);
                memoryQueryExtractDto.setLimit(20);
                List<String> tags = new ArrayList<>();
                HanLP.segment(inputMessage).forEach(term -> tags.add(term.word));
                tags.removeIf(tag -> tag.length() < 2);
                memoryQueryExtractDto.setTags(tags);
            }
            MemoryUnitQueryDTO memoryUnitQueryDTO = new MemoryUnitQueryDTO();
            BeanUtils.copyProperties(memoryQueryExtractDto, memoryUnitQueryDTO);
            memoryUnitQueryDTO.setUserId(userId);
            memoryUnitQueryDTO.setTenantId(tenantId);
            memoryUnitQueryDTO.setJustKeywordSearch(justKeywordSearch);
            memoryUnitQueryDTO.setAgentId(agentId);
            // 仅兜底路径强制 profile 画像必查（mergeProfileMemories）；LLM 提取成功时尊重其
            // shouldSearch/categories 检索意图，不额外强塞
            return memoryUnitService.queryList(memoryUnitQueryDTO);
        } catch (Exception e) {
            log.warn("查询记忆失败, userId {}, inputMessage {}", userId, inputMessage, e);
            return List.of();
        }
    }
}
