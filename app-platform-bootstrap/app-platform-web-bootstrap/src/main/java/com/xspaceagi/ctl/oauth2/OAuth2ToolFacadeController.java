package com.xspaceagi.ctl.oauth2;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedQueryDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecutingDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * OAuth2 门面 —— 工具组（已发布的插件与工作流）：检索、schema、执行。
 * <p>
 * scope：{@code tool:read}（两个读端点：检索与 schema）、{@code tool:exec}（三个执行端点共用一个 scope
 * ——执行插件与执行工作流对第三方的风险面同类，都是以授权用户身份触发一次可计费的服务端执行，
 * 再拆细只会让接入方多申请一个 scope 却得不到额外约束）。
 * <p>
 * 对应既有开放接口 {@code POST /api/v1/plugin/{id}/execute}、{@code /api/v1/workflow/{id}/execute}、
 * {@code /api/v1/workflow/{id}/sse/execute}（见 {@code PluginAndWorkflowApiController}）；
 * 检索与 schema 两个读端点的站内原型是沙箱开发接口 {@code POST /api/v1/4sandbox/agent/dev/tool/search}
 * （见 {@code AgentDevApiForSandboxController}）。
 *
 * <h2>★三处与原实现刻意不同</h2>
 *
 * <ol>
 * <li><b>不设 {@code apiKey}</b>。原实现取 {@code (UserAccessKeyDto) RequestContext.get().getUserAccessKey()}：
 * OAuth2 上下文里该值是 {@code null}，照抄会直接 NPE。而把它「补上」成 {@code oa-} 令牌更糟——
 * 该字段一路进 {@code TraceContext.apiKey}，{@code TokenLogService} 先用 {@code maskApiKey} 掩码、
 * 随后又用<b>原文</b>覆盖同名字段（builder 连调两次，后者生效）落进 token 日志，
 * 并且整个 {@code TraceContext} 会被序列化推进 Redis 的 {@code bill:queue}。
 * 把活令牌塞进去等于在日志与计费队列里存了一份可被直接使用的凭据。
 * 留空的代价只是日志里少了「哪把凭据发起的」这一维度，userId / tenantId / requestId 仍在。</li>
 * <li><b>执行失败不再被吞掉</b>。原实现 {@code ReqResult.success(resultDto.getResult())}：
 * 插件被计价拒绝时返回的是 {@code success=false, error=...}，但外层信封仍是成功、{@code result} 为 null，
 * 调用方无从判断「执行失败」与「执行成功但结果为空」。门面把 {@code success} / {@code error} /
 * {@code code} / {@code message} 一并回给调用方。</li>
 * <li><b>补 {@code blockLast()} 空值处理</b>。原实现用 {@code assert} 兜 null，而 JVM 默认不开断言，
 * 生产环境会退化成 NPE。</li>
 * </ol>
 *
 * <h2>★入参形状</h2>
 *
 * 路径必须是字面量（{@code OAuth2ScopeRegistry.allows} 按路径判 scope），因此工具 id 从路径挪到
 * <b>查询参数</b>，而 {@code params} 仍占<b>整个请求体</b>——不额外包一层 {@code {"params": {...}}}，
 * 以免与原开放接口的调用方代码产生第二套写法。
 *
 * <h2>★schema 是拿沙箱原型改的，三处不能照抄</h2>
 *
 * {@code /schema} 的描述文档以沙箱开发接口（{@code AgentDevApiForSandboxController} 的
 * {@code buildPluginSchema} / {@code buildWorkflowSchema}）为原型，但有三处必须改：
 * <ol>
 * <li>执行地址换成门面的 {@code /api/oauth2/tool/**}，不是沙箱的 {@code /api/v1/4sandbox/agent/**}；</li>
 * <li>凭据写成 {@code Bearer <access_token>}，而沙箱那版写的是 {@code ${SANDBOX_ACCESS_KEY}}——
 * 第三方接入应用<b>没有也不该有</b>沙箱 AK，照抄等于把一份站内凭据的名字写进对外契约；</li>
 * <li>响应结构照实写上<b>平台信封</b>。沙箱那版把出参属性直接摊在 {@code response.properties} 上，
 * 因为它的执行接口直接回工具结果；门面回的是 {@code {code, message, data}}，照抄会让调用方
 * 把信封的第一层当成输出对象本身。同理，{@code schema} 字段以<b>对象</b>形式返回，不是原型那样的
 * JSON 字符串——少一层转义，调用方不必再 {@code JSON.parse} 一次。</li>
 * </ol>
 */
@Tag(name = "OAuth2 门面-工具检索与执行", description = "需 OAuth2 access_token（oa- 前缀），scope：tool:read（检索 / schema）、tool:exec（执行）")
@RestController
@RequestMapping("/api/oauth2/tool")
@Slf4j
public class OAuth2ToolFacadeController {

    /** 工具执行不隶属任何智能体，沿用原实现的哨兵值 */
    private static final long NO_AGENT_ID = -1L;

    /** 工具检索默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 工具检索每页条数上限：防止超大 pageSize 把整库工具一次性拖出来 */
    private static final int MAX_PAGE_SIZE = 100;

    /** schema 里执行地址的站址占位符，与技能文档同一写法 */
    private static final String BASE_URL_PLACEHOLDER = "{PLATFORM_BASE_URL}";

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    // ================================================================== 工具检索 / schema

    /**
     * 检索授权用户<b>可执行</b>的已发布工具（插件 / 工作流）。
     * <p>
     * 可见范围与执行端点的放行口径<b>同一套</b>（{@link #checkPermission} 是同一个判据的逐条版本）：
     * 租户级发布 + 授权用户所属空间的空间级发布。
     * <p>
     * ★{@code type} 省略时查<b>两类</b>，且页码/每页条数对两类<b>各自生效</b>（返回条数最多
     * {@code pageSize × 2}、{@code total} 为两类之和）。这是照抄站内沙箱检索接口的形状：
     * 领域层的多类型合并查询（{@code targetTypes}）走的是另一条转换路径，插件 + 工作流这种组合
     * 不在它的既有使用面内，宁可按类型各查一次，也不去赌那条路径对 {@code targetType=null} 的处理。
     * 想按页稳定拿到 N 条就显式带上 {@code type}。
     *
     * @param type     {@code plugin} / {@code workflow}，省略为两类都查
     * @param spaceId  只看某个空间（★须是授权用户所属空间，否则 4030）；省略为「我能看到的全部」
     * @param kw       名称 / 描述关键字（数据库全文匹配，非模糊 Like）
     * @param page     页码，从 1 开始，缺省 1
     * @param pageSize 每页条数，缺省 20、上限 100
     */
    @Operation(summary = "检索已发布的工具（插件 / 工作流）")
    @GetMapping("/list")
    public ReqResult<ToolPageVo> toolList(@RequestParam(value = "type", required = false) String type,
                                          @RequestParam(value = "spaceId", required = false) Long spaceId,
                                          @RequestParam(value = "kw", required = false) String kw,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        List<Published.TargetType> targetTypes = parseToolTypes(type);
        long currentPage = page == null || page <= 0 ? 1 : page;
        long size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        List<Long> mySpaceIds = mySpaceIds();
        if (spaceId != null && !mySpaceIds.contains(spaceId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        List<ToolItemVo> items = new ArrayList<>();
        long total = 0;
        for (Published.TargetType targetType : targetTypes) {
            SuperPage<PublishedDto> result = publishApplicationService
                    .queryPublishedList(buildToolQuery(targetType, spaceId, mySpaceIds, kw, currentPage, size));
            total += result.getTotal();
            result.getRecords().forEach(publishedDto -> items.add(toToolItemVo(publishedDto)));
        }
        return ReqResult.success(new ToolPageVo(total, currentPage, size, items));
    }

    /**
     * 查询单个工具的出入参 schema。
     * <p>
     * 工具必须<b>已发布</b>且发布范围允许授权用户使用，判据与执行端点完全一致——否则会出现
     * 「schema 查得到、执行却 4030」的误导。
     * <p>
     * 返回的 {@code schema} 是自描述文档（执行地址 + 鉴权方式 + 入参 JSON Schema + 响应结构），
     * 可直接当工具定义喂给调用方自己的模型。入参里<b>只有</b>启用中的参数，且只有参数名、类型、
     * 是否必填、描述这些对外可用的部分——插件 / 工作流的内部绑定字段（{@code bindValue} 等）
     * 一律不出现在返回值里。
     *
     * @param type {@code plugin} / {@code workflow}（必填，不设默认：同名 id 在两类里是两条不同记录）
     * @param id   工具 ID（插件 ID 或工作流 ID）
     */
    @Operation(summary = "查询单个工具的出入参 schema")
    @GetMapping("/schema")
    public ReqResult<ToolSchemaVo> toolSchema(@RequestParam(value = "type", required = false) String type,
                                             @RequestParam(value = "id", required = false) Long id) {
        if (id == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "id");
        }
        Published.TargetType targetType = parseToolType(type);
        if (targetType == Published.TargetType.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(id, null);
            if (pluginDto == null) {
                return ReqResult.error("Error plugin id or not published");
            }
            checkPermission(pluginDto.getPublishedSpaceIds(), pluginDto.getScope());
            if (!(pluginDto.getConfig() instanceof PluginConfigDto pluginConfig)) {
                return ReqResult.error("Plugin config is not executable");
            }
            return ReqResult.success(new ToolSchemaVo(targetType.name(), id, pluginDto.getName(),
                    toolName(pluginDto.getFunctionName(), "plugin", id), pluginDto.getDescription(),
                    pluginDescriptor(id, pluginConfig.getInputArgs(), pluginConfig.getOutputArgs())));
        }
        WorkflowConfigDto configDto = workflowApplicationService.queryPublishedWorkflowConfig(id, null, false);
        if (configDto == null) {
            return ReqResult.error("Error workflow id or not published");
        }
        checkPermission(configDto.getPublishedSpaceIds(), configDto.getScope());
        return ReqResult.success(new ToolSchemaVo(targetType.name(), id, configDto.getName(),
                toolName(configDto.getFunctionName(), "workflow", id), configDto.getDescription(),
                workflowDescriptor(id, workflowInputArgs(configDto), workflowOutputArgs(configDto))));
    }

    // ================================================================== 检索 / schema 辅助

    /**
     * 解析 {@code type}：省略为「插件 + 工作流」两类，给了就只查该类。
     * <p>
     * 非法值<b>显式报错</b>而不是静默回落成两类：回落会让调用方把「拼错的 type」当成「查了全部」，
     * 拿到一份混着工作流的结果却以为全是插件。
     */
    private static List<Published.TargetType> parseToolTypes(String type) {
        return StringUtils.isBlank(type)
                ? List.of(Published.TargetType.Plugin, Published.TargetType.Workflow)
                : List.of(parseToolType(type));
    }

    /** 解析单个 {@code type}，必填；非法值报参数错误 */
    private static Published.TargetType parseToolType(String type) {
        String normalized = StringUtils.trimToNull(type);
        if (normalized == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "type");
        }
        for (Published.TargetType candidate : List.of(Published.TargetType.Plugin, Published.TargetType.Workflow)) {
            if (candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                "type must be one of: plugin, workflow");
    }

    /**
     * 授权用户所属空间 ID。用 {@code queryListByUserId}（鉴权口径）而不是
     * {@code queryVisibleListByUserId}——后者会漏掉私聊 / 群组镜像空间，导致明明有权限却看不着。
     */
    private List<Long> mySpaceIds() {
        List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        return spaces == null ? List.of()
                : spaces.stream().map(SpaceDto::getId).filter(spaceId -> spaceId != null).toList();
    }

    /**
     * 检索条件。
     * <p>
     * ★{@code spaceIds} 传的是<b>授权用户所属空间集合</b>而非空：领域层的判据是
     * 「{@code spaceIds} 非空 → {@code spaceId IN spaceIds OR scope = Tenant}，为空 → 只回 {@code scope = Tenant}」
     * （{@code PublishDomainServiceImpl}），所以不传等于把「我能用的空间级工具」整片漏掉。
     */
    private static PublishedQueryDto buildToolQuery(Published.TargetType targetType, Long spaceId,
                                                    List<Long> mySpaceIds, String kw, long page, long pageSize) {
        PublishedQueryDto query = new PublishedQueryDto();
        query.setTargetType(targetType);
        query.setOnlyTemplate(YesOrNoEnum.N.getKey());
        // config 是插件的代码 / 工作流的全部节点配置：列表用不上，体积却远大于其余字段之和
        query.setReturnConfig(false);
        query.setPage((int) page);
        query.setPageSize((int) pageSize);
        query.setKw(StringUtils.trimToNull(kw));
        if (spaceId != null) {
            query.setSpaceId(spaceId);
        } else {
            query.setSpaceIds(mySpaceIds);
        }
        return query;
    }

    /** 列表条目：★刻意不带 config（同 {@link #buildToolQuery}）、不带统计与发布者信息 */
    private static ToolItemVo toToolItemVo(PublishedDto dto) {
        Published.TargetType targetType = dto.getTargetType();
        String type = targetType == null ? null : targetType.name().toLowerCase();
        return new ToolItemVo(type, dto.getTargetId(), dto.getName(), dto.getDescription(),
                DefaultIconUrlUtil.setDefaultIconUrl(dto.getIcon(), dto.getName(), type, true),
                dto.getSpaceId(), dto.getScope() == null ? null : dto.getScope().name(), dto.getModified());
    }

    /**
     * 工具名：取自发布配置里的 {@code functionName}，为空时按平台的既有规则回落成
     * {@code plugin{id}} / {@code workflow{id}}（模型侧凭这个名字认工具，不能为空）。
     */
    private static String toolName(String functionName, String fallbackPrefix, Long id) {
        return StringUtils.isNotBlank(functionName) ? functionName : fallbackPrefix + id;
    }

    /**
     * 工作流入参：★取自 <b>Start 节点</b>的入参，不是 {@code WorkflowConfigDto.inputArgs}——
     * 后者在发布态配置里不带值（发布查询路径从不调用 {@code setInputArgs}），照抄会回一份空的入参定义。
     */
    private static List<Arg> workflowInputArgs(WorkflowConfigDto configDto) {
        WorkflowNodeDto startNode = configDto.getStartNode();
        if (startNode != null && startNode.getNodeConfig() != null
                && !CollectionUtils.isEmpty(startNode.getNodeConfig().getInputArgs())) {
            return startNode.getNodeConfig().getInputArgs();
        }
        return configDto.getInputArgs();
    }

    /** 工作流出参：取自 End 节点，理由同 {@link #workflowInputArgs} */
    private static List<Arg> workflowOutputArgs(WorkflowConfigDto configDto) {
        WorkflowNodeDto endNode = configDto.getEndNode();
        if (endNode != null && endNode.getNodeConfig() != null
                && !CollectionUtils.isEmpty(endNode.getNodeConfig().getOutputArgs())) {
            return endNode.getNodeConfig().getOutputArgs();
        }
        return configDto.getOutputArgs();
    }

    /** 入参 JSON Schema；无参时给一个空对象 schema，别让调用方去猜「没有 requestBody 是不是不能用」 */
    private static Map<String, Object> requestBodySchema(List<Arg> inputArgs) {
        return CollectionUtils.isEmpty(inputArgs)
                ? Map.of("type", "object", "properties", Map.of())
                : ArgConverter.convertArgsToJsonSchema(inputArgs);
    }

    /** 出参 JSON Schema：把 {@code ArgConverter} 的属性表挂到一个带描述的 object 下 */
    private static Map<String, Object> outputSchema(List<Arg> outputArgs, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(outputArgs)) {
            Map<String, Object> converted = ArgConverter.convertArgsToJsonSchema(outputArgs);
            if (converted.get("properties") instanceof Map<?, ?> convertedProperties) {
                convertedProperties.forEach((key, value) -> properties.put(String.valueOf(key), value));
            }
        }
        schema.put("properties", properties);
        return schema;
    }

    /**
     * 响应结构：★外层的平台信封照实写进来。
     * <p>
     * 沙箱原型没有这一层（它的执行接口直接回工具结果），照抄会让调用方以为
     * 「拿到的 JSON 就是输出对象」，实际拿到的第一层是 {@code data}。
     */
    private static Map<String, Object> envelope(Map<String, Object> dataProperties, String dataDescription) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "object");
        data.put("description", dataDescription);
        data.put("properties", dataProperties);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", Map.of("type", "string",
                "description", "\"0000\" 表示接口调用成功；工具本身成没成看 data.success"));
        properties.put("message", Map.of("type", "string", "description", "调用失败时的原因"));
        properties.put("data", data);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "object");
        wrapper.put("description", "平台信封，业务结果在 data 里");
        wrapper.put("properties", properties);
        return wrapper;
    }

    /** 插件描述文档：执行地址与凭据是本门面自己的，见类注释「★schema 里回给调用方的执行地址是本门面自己的」 */
    private static Map<String, Object> pluginDescriptor(Long pluginId, List<Arg> inputArgs, List<Arg> outputArgs) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", "POST");
        schema.put("url", BASE_URL_PLACEHOLDER + "/api/oauth2/tool/plugin/execute?pluginId=" + pluginId);
        schema.put("authorization", "Bearer <access_token>");
        schema.put("scope", "tool:exec");
        schema.put("contentType", "application/json");
        schema.put("requestBody", requestBodySchema(inputArgs));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", Map.of("type", "boolean",
                "description", "插件是否执行成功；false 时外层信封 code 仍是 \"0000\""));
        data.put("result", outputSchema(outputArgs, "插件输出，结构由插件自身定义"));
        data.put("error", Map.of("type", "string", "description", "失败原因，成功时为 null"));
        data.put("requestId", Map.of("type", "string", "description", "链路 ID（Trace-Id 头回显）"));
        data.put("costTime", Map.of("type", "integer", "description", "耗时（毫秒）"));
        schema.put("response", envelope(data, "插件执行结果"));
        return schema;
    }

    /** 工作流描述文档：同步端点 + 可选的 SSE 端点一并给出 */
    private static Map<String, Object> workflowDescriptor(Long workflowId, List<Arg> inputArgs, List<Arg> outputArgs) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", "POST");
        schema.put("url", BASE_URL_PLACEHOLDER + "/api/oauth2/tool/workflow/execute?workflowId=" + workflowId);
        schema.put("authorization", "Bearer <access_token>");
        schema.put("scope", "tool:exec");
        schema.put("contentType", "application/json");
        schema.put("requestBody", requestBodySchema(inputArgs));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", Map.of("type", "boolean", "description", "工作流执行成败"));
        data.put("data", outputSchema(outputArgs, "工作流输出"));
        data.put("code", Map.of("type", "string", "description", "工作流自己的业务码：\"0000\" 成功 / \"0001\" 失败"));
        data.put("message", Map.of("type", "string", "description", "失败原因"));
        data.put("requestId", Map.of("type", "string", "description", "链路 ID"));
        data.put("costTime", Map.of("type", "integer", "description", "耗时（毫秒）"));
        schema.put("response", envelope(data, "工作流执行结果"));

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "object");
        frame.put("description", "每一帧的 data 段（JSON，前缀一个空格）");
        Map<String, Object> frameProperties = new LinkedHashMap<>();
        frameProperties.put("success", Map.of("type", "boolean", "description", "本帧时的执行状态"));
        frameProperties.put("data", outputSchema(outputArgs, "工作流输出，完成帧才有"));
        frameProperties.put("code", Map.of("type", "string", "description", "工作流业务码"));
        frameProperties.put("message", Map.of("type", "string", "description", "失败原因"));
        frameProperties.put("requestId", Map.of("type", "string"));
        frameProperties.put("costTime", Map.of("type", "integer", "description", "耗时（毫秒）"));
        frameProperties.put("isComplete", Map.of("type", "boolean", "description", "true = 流结束；★失败也只推帧、不抛错"));
        frame.put("properties", frameProperties);

        Map<String, Object> streaming = new LinkedHashMap<>();
        streaming.put("url", BASE_URL_PLACEHOLDER + "/api/oauth2/tool/workflow/sse-execute?workflowId=" + workflowId);
        streaming.put("contentType", "text/event-stream");
        streaming.put("requestBody", "同本接口（不需带 Accept 头以外的额外字段）");
        streaming.put("stopWhen", "isComplete == true");
        streaming.put("frame", frame);
        schema.put("streaming", streaming);
        return schema;
    }

    // ================================================================== 插件

    /**
     * 执行已发布的插件（同步，阻塞至执行结束）。
     * <p>
     * 插件必须<b>已发布</b>（{@code queryPublishedPluginConfig} 取不到即拒），且发布范围允许授权用户使用
     * （{@link #checkPermission}）。草稿态插件不可通过本端点执行——否则第三方能跑到还没上线、
     * 未经审核的插件逻辑。
     *
     * @param pluginId 插件 ID（必填）
     * @param params   插件入参，占整个请求体；无参插件可传 {@code {}}
     */
    @Operation(summary = "执行已发布的插件（同步）")
    @PostMapping("/plugin/execute")
    public ReqResult<PluginResultVo> pluginExecute(@RequestParam("pluginId") Long pluginId,
                                                   @RequestBody(required = false) Map<String, Object> params,
                                                   HttpServletRequest request) {
        if (pluginId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "pluginId");
        }
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(pluginId, null);
        if (pluginDto == null) {
            throw new BizException("Error plugin id or not published");
        }
        checkPermission(pluginDto.getPublishedSpaceIds(), pluginDto.getScope());

        PluginExecuteRequestDto executeDto = new PluginExecuteRequestDto();
        executeDto.setPluginId(pluginId);
        executeDto.setParams(params == null ? new HashMap<>() : params);
        executeDto.setRequestId(requestId(request));
        executeDto.setAgentId(NO_AGENT_ID);
        executeDto.setTest(false);
        // ★刻意不设 apiKey，见类注释「三处与原实现刻意不同」第 1 条

        PluginExecuteResultDto result = pluginApplicationService.execute(executeDto, pluginDto);
        if (result == null) {
            return ReqResult.error("Plugin execution produced no result");
        }
        return ReqResult.success(new PluginResultVo(result.isSuccess(), result.getResult(), result.getError(),
                result.getRequestId(), result.getCostTime()));
    }

    // ================================================================== 工作流

    /**
     * 执行已发布的工作流（同步，阻塞至执行结束）。
     * <p>
     * ⚠️ 与原实现一样<b>没有超时</b>：长耗时工作流会一直占住请求线程与连接。
     * 耗时可能超过网关 / 客户端超时的场景请改用 {@link #workflowSseExecute}。
     */
    @Operation(summary = "执行已发布的工作流（同步）")
    @PostMapping("/workflow/execute")
    public ReqResult<WorkflowResultVo> workflowExecute(@RequestParam("workflowId") Long workflowId,
                                                       @RequestBody(required = false) Map<String, Object> params,
                                                       HttpServletRequest request) {
        if (workflowId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "workflowId");
        }
        WorkflowConfigDto configDto = workflowApplicationService.queryPublishedWorkflowConfig(workflowId, null, true);
        if (configDto == null) {
            return ReqResult.error("Error workflow id or not published");
        }
        checkPermission(configDto.getPublishedSpaceIds(), configDto.getScope());

        WorkflowExecutingDto executing = workflowApplicationService
                .executeWorkflow(buildWorkflowRequest(workflowId, params, request), configDto)
                .blockLast();
        if (executing == null) {
            return ReqResult.error("Workflow execution produced no output");
        }
        return ReqResult.success(toVo(executing));
    }

    /**
     * 执行已发布的工作流（SSE 流式，逐节点推送进度）。
     * <p>
     * 与原实现同构，包括两个容易被误改的细节：
     * <ul>
     * <li>每帧 data 前缀<b>一个空格</b>（{@code " " + JSON}）。SSE 规范里 {@code data:} 后的首个空格
     * 属于分隔符，客户端拿到的是去掉空格的内容；但这个前缀同时避免了某些客户端把以 {@code {} 开头的行
     * 当作非 JSON 处理，属于既有约定，不要「顺手清理」。</li>
     * <li>「工作流不存在 / 未发布」与「无权执行」<b>不抛异常</b>，而是在流内推一帧
     * {@code success=false, isComplete=true} 后正常结束。响应头已经按 SSE 发出，此时再抛异常
     * 只会让客户端收到一个半截的流 + 无法解析的错误。</li>
     * </ul>
     */
    @Operation(summary = "执行已发布的工作流（SSE 流式）")
    @PostMapping(value = "/workflow/sse-execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> workflowSseExecute(@RequestParam("workflowId") Long workflowId,
                                           @RequestBody(required = false) Map<String, Object> params,
                                           HttpServletRequest request, HttpServletResponse response) {
        // requestId 必须在返回 Flux 之前取：之后 Servlet 请求可能已被容器回收
        final String requestId = requestId(request);
        if (workflowId == null) {
            return Flux.create(emitter -> sendError(emitter, requestId, "workflowId is required"));
        }
        WorkflowConfigDto configDto = workflowApplicationService.queryPublishedWorkflowConfig(workflowId, null, true);
        if (configDto == null) {
            return Flux.create(emitter -> sendError(emitter, requestId, "Error workflow id or not published"));
        }
        try {
            checkPermission(configDto.getPublishedSpaceIds(), configDto.getScope());
        } catch (Exception e) {
            return Flux.create(emitter -> sendError(emitter, requestId, e.getMessage()));
        }
        response.setCharacterEncoding("utf-8");
        return workflowApplicationService
                .executeWorkflow(buildWorkflowRequest(workflowId, params, requestId), configDto)
                .map(executing -> " " + JSON.toJSONString(executing));
    }

    // ================================================================== 出入参

    /**
     * 工具检索结果页。
     *
     * @param total    命中条数——★{@code type} 省略时是两类之和
     * @param page     页码（回显请求值，缺省 1）
     * @param pageSize 每页条数（回显生效值，已按上限 100 收窄）
     * @param items    本页条目；★{@code type} 省略时是「两类各自的第一页」拼起来的，最多 {@code pageSize × 2} 条
     */
    public record ToolPageVo(long total, long page, long pageSize, List<ToolItemVo> items) {
    }

    /**
     * 工具条目。
     *
     * @param targetType  {@code plugin} / {@code workflow}，与请求参数 {@code type} 同形
     * @param targetId    工具 ID——★把 {@code targetType} + {@code targetId} 一起交给 {@code /tool/schema} 和两个执行端点
     * @param name        展示名
     * @param description 描述
     * @param icon        图标地址（缺省图标已补成可直接访问的地址）
     * @param spaceId     所属空间；租户级发布的工具此字段来自发布记录，未必是调用方所在的空间
     * @param scope       发布范围：{@code Tenant}（全租户可用）/ {@code Space}（仅发布空间可用）
     * @param publishDate 最后修改时间
     */
    public record ToolItemVo(String targetType, Long targetId, String name, String description, String icon,
                             Long spaceId, String scope, Date publishDate) {
    }

    /**
     * 工具 schema。
     *
     * @param targetType  {@code plugin} / {@code workflow}
     * @param targetId    工具 ID
     * @param name        展示名
     * @param toolName    工具名（发布配置里的 {@code functionName}，缺失时回落为 {@code plugin{id}} / {@code workflow{id}}）
     * @param description 描述
     * @param schema      自描述文档：执行地址、鉴权方式、scope、入参 JSON Schema、响应结构（工作流另含 SSE 端点）
     */
    public record ToolSchemaVo(String targetType, Long targetId, String name, String toolName, String description,
                               Map<String, Object> schema) {
    }

    /**
     * 插件执行结果。
     *
     * @param success   是否执行成功；<b>为 false 时外层信封仍是成功</b>，必须看这个字段
     * @param result    插件返回值（结构由插件自身定义）
     * @param error     失败原因（如订阅计价未通过）；成功时为 null
     * @param requestId 本次执行 ID，排查时提供给平台
     * @param costTime  耗时（毫秒）
     */
    public record PluginResultVo(boolean success, Object result, String error, String requestId, Long costTime) {
    }

    /**
     * 工作流执行结果。
     *
     * @param code {@code 0000}=成功，{@code 0001}=失败（与 {@code WorkflowExecutingDto.getCode()} 同源）
     */
    public record WorkflowResultVo(boolean success, Object data, String code, String message, String requestId,
                                   Long costTime) {
    }

    // ================================================================== 私有辅助

    /**
     * ★丢弃 {@code nodeExecuteResultMap}（各节点内部执行详情）与插件的 {@code logs}（执行日志）：
     * 这两项是给站内调试面板看的，对外返回等于把工作流内部结构、节点命名与运行日志一并暴露，
     * 而调用方只需要最终结果与失败原因。
     */
    private static WorkflowResultVo toVo(WorkflowExecutingDto executing) {
        return new WorkflowResultVo(executing.isSuccess(), executing.getData(), executing.getCode(),
                executing.getMessage(), executing.getRequestId(), executing.getCostTime());
    }

    private WorkflowExecuteRequestDto buildWorkflowRequest(Long workflowId, Map<String, Object> params,
                                                           HttpServletRequest request) {
        return buildWorkflowRequest(workflowId, params, requestId(request));
    }

    private static WorkflowExecuteRequestDto buildWorkflowRequest(Long workflowId, Map<String, Object> params,
                                                                  String requestId) {
        WorkflowExecuteRequestDto executeDto = new WorkflowExecuteRequestDto();
        executeDto.setWorkflowId(workflowId);
        executeDto.setParams(params == null ? new HashMap<>() : params);
        executeDto.setRequestId(requestId);
        executeDto.setAgentId(NO_AGENT_ID);
        // ★刻意不设 apiKey，见类注释「三处与原实现刻意不同」第 1 条
        return executeDto;
    }

    /**
     * 执行 ID：优先取请求头 {@code Trace-Id}（便于调用方把自己的链路串进来），缺省生成一个。
     * 与原实现同源，因此同一段日志在两条路径下的检索方式一致。
     */
    private static String requestId(HttpServletRequest request) {
        String traceId = request == null ? null : request.getHeader("Trace-Id");
        return StringUtils.isNotBlank(traceId) ? traceId.trim() : UUID.randomUUID().toString().replace("-", "");
    }

    /** SSE 流内错误帧：结构与正常帧一致，客户端只需一套解析逻辑 */
    private static void sendError(FluxSink<String> emitter, String requestId, String message) {
        WorkflowExecutingDto executing = new WorkflowExecutingDto();
        executing.setSuccess(false);
        executing.setRequestId(requestId);
        executing.setMessage(message);
        executing.setComplete(true);
        emitter.next(" " + JSON.toJSONString(executing));
        emitter.complete();
    }

    /**
     * 发布范围校验：租户级发布直接放行；空间级发布要求授权用户至少属于其中一个发布空间。
     * <p>
     * 与 {@code PluginAndWorkflowApiController.checkPermission} 同逻辑（含「publishedSpaceIds 为空即放行」
     * 这一既有口径——空集合同时出现在「租户级发布」与历史脏数据上，改成拒绝会误伤存量）。
     * 必须用 {@code queryListByUserId}（鉴权口径）而不是 {@code queryVisibleListByUserId}，
     * 后者会漏掉私聊 / 群组镜像空间导致误判无权。
     */
    private void checkPermission(List<Long> publishedSpaceIds, Published.PublishScope scope) {
        if (scope == Published.PublishScope.Tenant) {
            return;
        }
        if (CollectionUtils.isEmpty(publishedSpaceIds)) {
            return;
        }
        List<Long> userSpaceIds = mySpaceIds();
        boolean anyMatch = publishedSpaceIds.stream()
                .anyMatch(spaceId -> spaceId != null && userSpaceIds.contains(spaceId));
        if (!anyMatch) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }
}
