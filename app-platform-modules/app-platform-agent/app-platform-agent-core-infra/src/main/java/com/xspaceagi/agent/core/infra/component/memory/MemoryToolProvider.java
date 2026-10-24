package com.xspaceagi.agent.core.infra.component.memory;

import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.memory.sdk.dto.MemorySaveDto;
import com.xspaceagi.memory.sdk.dto.MemoryUnitQueryDTO;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆的<b>原生工具清单 + 执行</b>（memory_query / memory_save / memory_delete）。
 *
 * <p>原先这三个工具内联在 {@code MasterAgentLoop}（总控编排）里；chatbot 那条腿
 * （{@code HybridAgentLoop}）只有「被动记忆」——开轮时 {@code loadLongMemory} 把命中的记忆塞进
 * {@code <user-memory>}，收尾时 {@code pushMemorySummary} 异步提炼，模型自己<b>无法</b>查/写/删。
 * 于是「帮我记一下」「我上次说的那个手机号是多少」「把那条忘掉」在 chatbot 上要么答不上、要么答错。
 * 抽到本类后两个 loop 共用同一份清单、同一套执行、同一段纪律提示词。
 *
 * <h2>为什么必须抽公共件而不是各抄一份</h2>
 *
 * <p>工具 description 里带着完整分类体系（{@link #MEMORY_TAXONOMY}，几十行）与 5 条写入规则，
 * 系统提示词里带着 memoryId 严禁编造等纪律（{@link #MEMORY_PROMPT_SECTION}）。这些东西<b>抄两份必然漂移</b>，
 * 而漂移的表现不是报错，是「同一个智能体在总控和 chatbot 上记忆行为不一样」——排查时两边都得读一遍才知道差在哪。
 *
 * <h2>★三个工具都是「结果回喂型」控制工具</h2>
 *
 * <p>{@link ComponentTypeEnum#Control} 会被 {@code ModelInvoker} 强制 {@code returnDirect=true}：
 * 模型调它<b>不产生任何用户可见输出</b>，只是把入参作为控制信号交回 loop。所以本类的三个方法
 * 只负责「执行 + 返回结果文本」，<b>不负责输出</b>；调用方（loop）必须走完这三步，少一步用户就什么都收不到：
 * <ol>
 *   <li>{@code recordToolCall(action, rawJson)} —— assistant(tool_use) 入 toolChain；</li>
 *   <li>本类方法拿到 {@link Op#result()} 后 {@code appendToolResult(action, result)} —— 与上一步按 toolCallId 配对；</li>
 *   <li>把 {@link Op#instruction()} 作为追加提示<b>再调一次模型</b>（Master 的 {@code continueWithContext} /
 *       Hybrid 的同名方法），由模型基于结果最终回答用户。</li>
 * </ol>
 *
 * <p>★第 3 步是这三个工具<b>唯一</b>的出口：不回喂就等于「调了工具、结果扔了、sink 永不 complete」。
 * 沙箱那条腿（{@code handleSandbox}）是自答型、直接往 sink 写输出，两者不能照抄。
 *
 * <h2>★挂载闸门：{@link #isOpen(AgentContext)}</h2>
 *
 * <p>唯一权威判据是 {@code agentConfig.openLongMemory == Open}（用户/租户/会话上都<b>没有</b>记忆开关，
 * {@code ctx.getLongMemory()} 只是本轮命中的记忆正文，不能当开关用）。
 * 挂工具与注提示词段<b>必须同源用这一个判据</b>：只挂工具不注纪律，模型会编造 memoryId 去删别人的记忆；
 * 只注纪律不挂工具，模型会去调一个不存在的函数名。
 *
 * <p>注意 {@code ctx.getAgentConfig()} 在两个 loop 里都是非空不变量（{@code buildModelContext} 更早就会因
 * 模型配置缺失抛 {@code BizException}），这里的判空只是不让「配置异常」升级成整轮 NPE。
 */
@Slf4j
@Component
public class MemoryToolProvider {

    /** memory_query 的规范工具名。★与 {@code recordToolCall}/{@code appendToolResult} 三处必须同名，否则配对断裂。 */
    public static final String ACTION_QUERY = "memory_query";
    /** memory_save 的规范工具名。 */
    public static final String ACTION_SAVE = "memory_save";
    /** memory_delete 的规范工具名。 */
    public static final String ACTION_DELETE = "memory_delete";

    /**
     * 三个记忆工具名（loop 侧用于「这是不是记忆动作」的判定，与 {@link #isMemoryAction} 共用一份名单）。
     */
    public static final List<String> ACTIONS = List.of(ACTION_QUERY, ACTION_SAVE, ACTION_DELETE);

    /**
     * 记忆管理提示词段（仅智能体开启长期记忆时注入系统提示词）。
     * 分类体系与写入规则详见 {@link #MEMORY_TAXONOMY}（随 memory_save 工具描述下发）。
     * <p>
     * ★必须与工具挂载<b>同源门控</b>：未挂工具却注入本段 = 让模型去调不存在的函数名。
     */
    public static final String MEMORY_PROMPT_SECTION = """
            ## 记忆管理
            你可以管理用户的长期记忆：
            - 用户询问其个人信息、偏好、任务安排等而上下文中没有答案 → 调用 memory_query 查询后回答
            - 用户要求记住某事，或提供了值得长期保存的事实（个人信息、偏好、任务、日程等）→ 调用 memory_save 写入
            - 用户要求删除/忘掉某条记忆 → 若上下文中还没有对应记忆的 memoryId，先调用 memory_query 定位，再调用 memory_delete 删除

            记忆操作约束：
            - memoryId 只能来自上下文或 memory_query 的查询结果，严禁编造。
            - 事实变更（如换手机号、偏好改变）时必须更新旧记忆（memory_save 的 updateMemoryKeyValues），禁止新建与之冲突的记忆。
            - 记忆操作完成后向用户简短确认即可，不要提及 memoryId 等内部细节。

            【重要】调用记忆相关的工具前无需其他内容输出！
            """;

    /**
     * 记忆完整分类体系（一级分类 + 二级分类 + 可用键名），作为 memory_save 工具描述的一部分，
     * 指导模型正确选择 category/subCategory 并使用规范键名。
     * 内容取自记忆模块 {@code MemoryApplicationServiceImpl#MEMORY_EXTRACT_PROMPT} 的分类体系定义。
     */
    public static final String MEMORY_TAXONOMY = """
            一级分类及含义：profile(用户画像)、auth(认证信息)、social(社交媒体)、project(项目信息)、preference(用户偏好)、task(任务待办)、note(笔记备忘)、idea(创意想法)、event(事件日程)、document(文档资料)、code(代码技术)、other(其他类型)。
            二级分类及可用键名（keyValues 的键必须从对应子分类的键名中选择）：
            - profile: personal(name, age, gender, birthday, avatar); professional(company, position, department, title, employee_id); location(city, address, timezone, country, coordinates); contact(phone, email, wechat, telegram, slack); identity(id_card, passport, license, ssn, tax_id); account(account_id, phone, email, platform)
            - auth: website(url, username, password, login_method); service(service_name, api_key, app_id, app_secret, endpoint); api_key(key, secret, permissions, expiry_date); ssh_key(private_key, public_key, host, username, port); oauth_token(access_token, refresh_token, token_type, expires_in); database(host, port, username, password, database, connection_string)
            - social: wechat(account_id, nickname, contacts, groups); twitter(username, handle, followers, following); linkedin(profile_url, connections, headline); contacts(name, relation, contact_info, notes); groups(group_name, members, topic, platform)
            - project: ongoing(name, description, progress, start_date, owner); completed(name, completion_date, summary, outcome); planning(name, timeline, requirements, budget); milestone(name, deadline, status, dependencies); task(project_id, title, assignee, deadline, status)
            - preference: travel(transport, airline, hotel_type, travel_style); food(cuisine, restrictions, favorites, dislikes); lifestyle(sleep_time, exercise, hobbies, daily_routine); entertainment(music, movies, games, sports); work(working_hours, tools, environment, work_style); general(general_preference, preference_name, value, notes)
            - task: daily(title, due_date, priority, description); weekly(title, week, status, category); project(project_id, title, assignee, status); urgent(title, deadline, priority, impact); recurring(title, frequency, next_due, pattern)
            - note: meeting(date, participants, summary, action_items); lecture(topic, key_points, references, instructor); research(subject, findings, sources, methodology); personal(topic, content, tags, created_at)
            - idea: product(name, description, features, target_users); feature(component, enhancement, benefit, complexity); improvement(area, problem, solution, priority); innovation(concept, potential, challenges, feasibility)
            - event: meeting(title, time, location, attendees, agenda); appointment(title, time, location, purpose); reminder(title, time, repeat, notification_method); deadline(task, due_date, priority, consequences)
            - document: contract(title, parties, effective_date, expiry_date); invoice(number, amount, due_date, vendor, status); report(title, date, summary, author); reference(title, author, url, tags, category)
            - code: snippet(language, description, code, filename); config(service, environment, settings, format); api_doc(endpoint, method, parameters, response_format); deployment(environment, version, changes, deploy_date)
            - other: 不属于以上分类时使用，键名自拟(如 topic, content)
            """;

    /** memory_query 之后回喂给模型的指令。 */
    private static final String INSTRUCTION_QUERY = "以上是记忆查询结果（其中 memory_id 列可用于 memory_save 更新与 memory_delete 删除）。"
            + "请基于查询结果直接回答用户或继续执行记忆操作；未命中时如实告知，不要编造记忆内容。";
    /** memory_save 之后回喂给模型的指令。 */
    private static final String INSTRUCTION_SAVE = "以上是记忆写入结果。请向用户简短确认（不要提及 memoryId、分类编码等内部细节）；写入失败时如实说明。";
    /** memory_delete 之后回喂给模型的指令。 */
    private static final String INSTRUCTION_DELETE = "以上是记忆删除结果。请向用户简短确认（不要提及 memoryId 等内部细节）；失败时如实说明。";

    @Resource
    private IMemoryRpcService memoryRpcService;

    /**
     * 单测注入用（同包可见）：生产由 {@code @Resource} 装配。
     */
    void setMemoryRpcService(IMemoryRpcService memoryRpcService) {
        this.memoryRpcService = memoryRpcService;
    }

    // ======================== 闸门 ========================

    /**
     * 该智能体是否开启长期记忆 —— 记忆工具挂载、记忆纪律提示词注入的<b>唯一</b>判据，
     * 与 {@code loadLongMemory}（被动加载 {@code <user-memory>}）的门控一致。
     */
    public boolean isOpen(AgentContext ctx) {
        return ctx != null && ctx.getAgentConfig() != null
                && ctx.getAgentConfig().getOpenLongMemory() == AgentConfig.OpenStatus.Open;
    }

    /**
     * 是否记忆类控制动作（loop 侧分发用）。
     */
    public boolean isMemoryAction(String action) {
        return action != null && ACTIONS.contains(action);
    }

    // ======================== 工具清单 ========================

    /**
     * 记忆管理控制工具（memory_query / memory_save / memory_delete）。
     * <p>
     * 接口对应 {@link IMemoryRpcService#queryMemoriesMd} / {@link IMemoryRpcService#saveMemories} /
     * {@link IMemoryRpcService#deleteMemories}；分类体系与写入规则取自
     * {@code MemoryApplicationServiceImpl#MEMORY_EXTRACT_PROMPT}（见 {@link #MEMORY_TAXONOMY}）。
     * <p>
     * 注意：{@code ArgConverter.convertArgsToJsonSchema} 对 Object/Array_Object 节点不输出 description，
     * 因此分类体系等长文本指导放在工具 description（会完整进入模型 function schema），
     * 嵌套节点仅靠叶子参数的 description 自解释。
     * <p>
     * ★每次调用都<b>新建</b> {@link ComponentConfig} 与 {@link Arg} 实例（与 {@code buildSandboxTools()} 一致）：
     * {@code ArgExtractUtil.setArgDefaultValue} 会就地改写 Arg，共享实例会让上一轮的改写渗到下一轮。
     */
    public List<ComponentConfig> buildTools() {
        List<ComponentConfig> tools = new ArrayList<>();

        // memory_query：查询用户长期记忆
        ComponentConfig memoryQuery = new ComponentConfig();
        memoryQuery.setType(ComponentTypeEnum.Control);
        memoryQuery.setFunctionName(ACTION_QUERY);
        memoryQuery.setName("记忆查询");
        memoryQuery.setDescription("查询用户的长期记忆。当用户询问其个人信息、偏好、任务、历史安排等而当前上下文没有答案时，"
                + "或需要获取 memoryId 以更新/删除记忆时调用。"
                + "categories/subCategories 必须严格从 memory_save 工具描述中的分类体系中选择；"
                + "tags 为关键词标签，分词可以细粒度一点，尽量多生成有助于更精确查找。");
        memoryQuery.setTargetId(0L);
        memoryQuery.setInputArgs(List.of(
                Arg.builder().name("categories").description("一级分类列表(可选)，缩小查询范围时填入，必须严格从分类体系中选择").dataType(DataTypeEnum.Array_String).require(false).build(),
                Arg.builder().name("subCategories").description("二级分类列表(可选)，必须严格从分类体系中选择").dataType(DataTypeEnum.Array_String).require(false).build(),
                Arg.builder().name("tags").description("关键词标签列表，分词可以细粒度一点，尽量多生成有助于更精确查找").dataType(DataTypeEnum.Array_String).require(false).build(),
                Arg.builder().name("queryType").description("查询范围，可选值为：today|yesterday|recent|long-term|all，非必填").dataType(DataTypeEnum.String).require(false).build(),
                Arg.builder().name("timeRange").description("时间范围(非必填)，格式为：2023-01-01 00:00:00-2023-01-01 23:59:59").dataType(DataTypeEnum.String).require(false).build(),
                Arg.builder().name("limit").description("限制返回的记忆数量，默认为10").dataType(DataTypeEnum.Integer).require(false).build()
        ));
        tools.add(memoryQuery);

        // memory_save：记忆新增或更新
        ComponentConfig memorySave = new ComponentConfig();
        memorySave.setType(ComponentTypeEnum.Control);
        memorySave.setFunctionName(ACTION_SAVE);
        memorySave.setName("记忆新增或更新");
        memorySave.setDescription("新增或更新用户的长期记忆。当用户要求记住某事，或提供了值得长期保存的事实(个人画像、偏好、任务待办、日程事件等)时调用。"
                + "写入规则：1)区分指令与事实：用户说'帮我记住X/记到记忆里'时，存的是X本身的内容，不要存这句话本身；"
                + "2)不要将AI助手自身的名称、身份描述(如'我是XX助手')识别为用户画像；"
                + "3)时间类字段一律给出具体日期，无法确定时标为N/A，不要存'已过期3天'这类相对表述；"
                + "4)密码、密钥等敏感信息 isSensitive 设为 true；"
                + "5)事实变更(如换手机号、偏好改变)必须先用 memory_query 查到旧记忆的 memoryId，通过 updateMemoryKeyValues 更新"
                + "(keyValues 为更新后应保留的全部字段：被新事实推翻的旧字段用新值覆盖或直接移除，未变化的保留)，严禁新建与之冲突的记忆；"
                + "仅当与已有记忆无关且值得保存时才放入 insertMemories。"
                + "完整分类体系(category/subCategory 必须严格从中选择)：" + MEMORY_TAXONOMY);
        memorySave.setTargetId(0L);
        memorySave.setInputArgs(List.of(
                Arg.builder().name("insertMemories").description("新增记忆列表(与 updateMemoryKeyValues 至少提供一项)").dataType(DataTypeEnum.Array_Object).require(false)
                        .subArgs(List.of(
                                Arg.builder().name("category").description("一级分类，必须严格从分类体系中选择").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("subCategory").description("二级分类，必须严格从分类体系中选择").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("keyValues").description("记忆键值对，键名必须使用分类体系中对应子分类列出的可用键名").dataType(DataTypeEnum.Object).require(true).build(),
                                Arg.builder().name("tags").description("关键词标签列表").dataType(DataTypeEnum.Array_String).require(false).build(),
                                Arg.builder().name("isSensitive").description("是否为敏感信息(密码、密钥等为true)").dataType(DataTypeEnum.Boolean).require(false).build()
                        ))
                        .build(),
                Arg.builder().name("updateMemoryKeyValues").description("更新记忆列表，memoryId 必须来自 memory_query 查询结果").dataType(DataTypeEnum.Array_Object).require(false)
                        .subArgs(List.of(
                                Arg.builder().name("memoryId").description("要更新的记忆ID，必须来自 memory_query 查询结果，严禁编造").dataType(DataTypeEnum.Integer).require(true).build(),
                                Arg.builder().name("keyValues").description("更新后应保留的全部字段的键值对(整体替换原有内容)").dataType(DataTypeEnum.Object).require(true).build(),
                                Arg.builder().name("tags").description("关键词标签列表").dataType(DataTypeEnum.Array_String).require(false).build(),
                                Arg.builder().name("isSensitive").description("是否为敏感信息(密码、密钥等为true)").dataType(DataTypeEnum.Boolean).require(false).build()
                        ))
                        .build()
        ));
        tools.add(memorySave);

        // memory_delete：删除用户长期记忆
        ComponentConfig memoryDelete = new ComponentConfig();
        memoryDelete.setType(ComponentTypeEnum.Control);
        memoryDelete.setFunctionName(ACTION_DELETE);
        memoryDelete.setName("记忆删除");
        memoryDelete.setDescription("删除用户的长期记忆。当用户明确要求删除/忘掉某条记忆时调用。"
                + "memoryIds 必须来自 memory_query 查询结果中的 memory_id；若当前上下文没有对应查询结果，先调用 memory_query 定位后再删除。");
        memoryDelete.setTargetId(0L);
        memoryDelete.setInputArgs(List.of(
                Arg.builder().name("memoryIds").description("要删除的记忆ID列表，必须来自 memory_query 查询结果中的 memory_id").dataType(DataTypeEnum.Array_Integer).require(true).build()
        ));
        tools.add(memoryDelete);

        return tools;
    }

    // ======================== 执行（三个方法都不抛异常：失败也返回可回喂的结果文本） ========================

    /**
     * 记忆查询。
     * <p>
     * 接口：{@link IMemoryRpcService#queryMemoriesMd}。tenantId/userId/agentId 由服务端从上下文填充
     * （模型只给查询条件），filterSensitive 沿用 {@link AgentContext#isFilterSensitive()}（对齐 loadLongMemory）。
     * 返回的 Markdown 表格含 memory_id 列，供后续 memory_save(更新)/memory_delete 引用。
     */
    public Op query(AgentContext ctx, JSONObject control) {
        String result;
        try {
            MemoryUnitQueryDTO queryDTO = control.to(MemoryUnitQueryDTO.class);
            queryDTO.setTenantId(ctx.getUser().getTenantId());
            queryDTO.setUserId(ctx.getUser().getId());
            queryDTO.setAgentId(ctx.getAgentConfig().getId());
            String md = TenantFunctions.callWithIgnoreCheck(() -> memoryRpcService.queryMemoriesMd(queryDTO, ctx.isFilterSensitive()));
            result = StringUtils.isBlank(md) ? "未找到相关记忆。" : md;
        } catch (Exception e) {
            log.warn("[MemoryToolProvider] memory_query failed: {}", e.getMessage(), e);
            result = "记忆查询失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知错误");
        }
        log.info("[MemoryToolProvider] memory_query result length={}", result.length());
        return new Op(ACTION_QUERY, result, INSTRUCTION_QUERY);
    }

    /**
     * 记忆新增或更新。
     * <p>
     * 接口：{@link IMemoryRpcService#saveMemories}。入参直接映射 {@link MemorySaveDto}
     * （insertMemories 新增 / updateMemoryKeyValues 按 memoryId 整体替换更新）。
     * 分类体系与写入规则见 {@link #MEMORY_TAXONOMY}（memory_save 工具描述）。
     */
    public Op save(AgentContext ctx, JSONObject control) {
        String result;
        try {
            MemorySaveDto saveDto = control.to(MemorySaveDto.class);
            // 统计有效条目（与 memory 服务端 insertMemory/updateMemory 的校验条件一致），避免空/无效条目被报成"写入成功"
            long validInserts = saveDto.getInsertMemories() == null ? 0 : saveDto.getInsertMemories().stream()
                    .filter(u -> u.getCategory() != null && u.getSubCategory() != null
                            && u.getKeyValues() != null && !u.getKeyValues().isEmpty())
                    .count();
            long validUpdates = saveDto.getUpdateMemoryKeyValues() == null ? 0 : saveDto.getUpdateMemoryKeyValues().stream()
                    .filter(u -> u.getMemoryId() != null && u.getKeyValues() != null && !u.getKeyValues().isEmpty())
                    .count();
            if (validInserts == 0 && validUpdates == 0) {
                result = "记忆写入未执行：insertMemories 与 updateMemoryKeyValues 均为空或字段不完整"
                        + "（新增需 category/subCategory/keyValues，更新需 memoryId/keyValues）。请补全后重试。";
            } else {
                TenantFunctions.runWithIgnoreCheck(() -> memoryRpcService.saveMemories(ctx.getUser().getTenantId(), ctx.getUser().getId(),
                        ctx.getAgentConfig().getId(), saveDto));
                result = String.format("记忆写入完成：新增 %d 条，更新 %d 条。", validInserts, validUpdates);
            }
        } catch (Exception e) {
            log.warn("[MemoryToolProvider] memory_save failed: {}", e.getMessage(), e);
            result = "记忆写入失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知错误");
        }
        log.info("[MemoryToolProvider] memory_save result: {}", result);
        return new Op(ACTION_SAVE, result, INSTRUCTION_SAVE);
    }

    /**
     * 记忆删除。
     * <p>
     * 接口：{@link IMemoryRpcService#deleteMemories}（软删，按 tenantId+userId+memoryIds 校验归属）。
     * memoryIds 必须来自 memory_query 查询结果（工具描述与系统提示词均有约束），空列表直接回错喂给模型
     * ——<b>不能</b>当成"删了 0 条、成功"，那会让模型对用户说"已删除"。
     */
    public Op delete(AgentContext ctx, JSONObject control) {
        String result;
        List<Long> memoryIds = control.to(DeleteCmd.class).getMemoryIds();
        if (CollectionUtils.isEmpty(memoryIds)) {
            result = "记忆删除未执行：memoryIds 为空。memoryId 必须来自 memory_query 查询结果的 memory_id 列，请先查询确认后再删除。";
        } else {
            try {
                TenantFunctions.runWithIgnoreCheck(() -> memoryRpcService.deleteMemories(ctx.getUser().getTenantId(), ctx.getUser().getId(), memoryIds));
                result = String.format("记忆删除完成：已删除 %d 条。", memoryIds.size());
            } catch (Exception e) {
                log.warn("[MemoryToolProvider] memory_delete failed: {}", e.getMessage(), e);
                result = "记忆删除失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知错误");
            }
        }
        log.info("[MemoryToolProvider] memory_delete result: {}", result);
        return new Op(ACTION_DELETE, result, INSTRUCTION_DELETE);
    }

    /**
     * 一次记忆工具执行的结果：{@code result} 追加进 toolChain 与 tool_use 配对，
     * {@code instruction} 作为二次调模型时追加到 userPrompt 末尾的临时指令（两侧 loop 共用同一份文案）。
     */
    public record Op(String action, String result, String instruction) {
    }

    /**
     * memory_delete 的入参载体（只为把 {@code memoryIds} 从控制信号 JSON 里取出来）。
     */
    @Data
    public static class DeleteCmd {
        private List<Long> memoryIds;
    }
}
