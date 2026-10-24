package com.xspaceagi.ctl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerPodClient;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.ctl.dto.*;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.service.VirtualIdentityApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.MD5;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.xspaceagi.im.infra.enums.ImChannelEnum.NUWAX_IM;

/**
 * 内部服务间 HTTP API：供平台内其他项目（server-to-server）按租户做只读查询。
 * <p>
 * 认证与租户语义（本控制器的核心契约）：
 * <ul>
 *   <li><b>认证</b>：请求头 {@code X-Api-Key} 对配置 {@code internal.api.key} 的静态共享密钥，
 *       由 {@code InternalApiInterceptor} 在进控制器前完成（未配置 = 未启用，一律拒绝）。
 *       与用户级 ak-/ck- AccessKey 体系无关。</li>
 *   <li><b>租户</b>：每个接口都要求显式 {@code tenantId} 入参，进查询前覆盖请求上下文
 *       （豁免登录的路径默认落 tenantId=1，必须覆盖，否则租户隔离查询错位——同
 *       RepoInternalAuthController 的处理）。覆盖后大多数 SQL 会被 MP 租户拦截器自动限定在
 *       该租户内；但<b>用户域的底层查询是刻意豁免租户过滤的</b>（登录前按 userId 查用户、
 *       按 id 批量查用户都必须跨租户可用，见 UserDomainServiceImpl#queryById 的
 *       TenantFunctions.callWithIgnoreCheck 与 queryUserListByIds 的 addTenantIgnoreEntity），
 *       所以本控制器对每一行结果都再做一次显式归属校验：租户不匹配一律当作「查不到」
 *       （detail 返回 null、list 跳过该行）。不能依赖底层 SQL 保证 fail-closed。</li>
 *   <li><b>只读为主</b>：除下面几组受控写接口外，其余全部为查询接口。
 *       <br>写接口一：{@code POST /user/virtual-identity/create}（创建/复用虚拟身份）。它的边界是
 *       <b>只能创建虚拟主体</b>——{@code accountType} 只接受 agent / group，传 user 一律拒绝
 *       （否则等于给调用方开了凭空造自然人的口子）；且以「租户 + 类型 + principalRefId」幂等，
 *       重复调用返回同一个身份 id，不做任何更新或删除。
 *       <br>写接口二：{@code POST /agent/conversation/create}（按租户 + 用户ID 创建会话）。
 *       只建空会话，不产生任何模型调用与费用；话题名、变量等由后续
 *       {@code POST /agent/conversation/chat} 驱动。
 *       <br>写接口三：{@code POST /agent/conversation/chat}（按租户 + 用户ID 发起会话，SSE 流式）。
 *       <br>写接口四：空间与成员增删——{@code POST /space/create}、{@code POST /space/delete}、
 *       {@code POST /space/user/add}、{@code POST /space/user/delete}。这组接口当前<b>只对
 *       Group（群组）与 DM（私聊）空间开放</b>：创建只接受 {@code type=Group|DM}（3039），
 *       删除也只允许这两种（3038），个人空间一律不接受成员增删（3040）。空间类型是枚举列，
 *       Group 与 DM 取值分别由 {@code update-20260919-space-type-group.sql} 与
 *       {@code update-20260919-space-type-dm.sql} 追加在末尾（见 {@link Space.Type}）。
 *       两者管控完全一致，唯一差别是 DM 创建时可选传 {@code peerUserId} 把「对方」一并写成成员。
 *       <b>删除空间是底层既有的物理删除</b>：删空间行与成员行，并向两个 Redis 队列推送清理消息
 *       （发布物 / 文档），不可逆，且清理是异步的。创建者必须由调用方显式指定
 *       （{@code ownerUserId}），建后自动成为 Owner 成员——内部接口没有登录态，无法自行推导。
 *       <br>写接口五：{@code POST /agent/computer/restart}（按租户 + 用户ID + 会话ID 重启智能体电脑，
 *       销毁容器后重建）。这是本控制器<b>唯一会丢运行态的破坏性接口</b>，边界是「会话必须属于调用方声明的
 *       那个 userId」——少了这条，任何持有内部密钥的调用方都能拿一个 userId 去重启别人的容器。
 *       容器侧的成败一律<b>透传容器服务的码</b>（口径同页面端 {@code /api/computer/pod/restart}），
 *       仅在平台自己就能判定失败时用 4000/9309，详见方法注释。</li>
 *   <li><b>代用户身份（prompt 的关键）</b>：{@code POST /agent/conversation/create} 与
 *       {@code POST /agent/conversation/chat} 都是「代指定用户操作」。会话的权限校验、归属、
 *       计费主体一律从 {@link RequestContext} 读取当前用户，无登录态时必然失败，因此接口会在
 *       校验通过后把目标用户写入上下文再调用底层服务（同 IM 渠道
 *       {@code WeworkAgentApplicationServiceImpl#executeAgentWithConv} 的生产做法），
 *       并在结束后恢复/清理，不把身份泄漏到同一请求的后续逻辑。
 *       代价是<b>调用方对 userId 的正确性负全责</b>：接口只校验该用户属于声明的租户，
 *       不会也无法判断「调用方是否有权代表这个人」——那是调用方业务侧的事。</li>
 *   <li><b>SSE 会话</b>：{@code POST /agent/conversation/chat} 返回 {@code text/event-stream}
 *       （与 {@code /api/agent/conversation/chat} 同构，逐事件透传），调用方必须支持 SSE。
 *       流式响应在异步线程产出，而 {@code AuthInterceptor} 是在请求线程结束时清理上下文的，
 *       所以该接口在 {@code Flux.defer} 内按订阅线程重建整套上下文
 *       （tenantId/tenantConfig/user/userId/billUserId），并在流终止时 {@code RequestContext.remove()}。
 *       计费主体的语义见 {@code ChatApplicationServiceImpl#estimatePrice}。</li>
 *   <li><b>敏感字段</b>：用户查询走 {@link UserDetailDto}，password/resetPass 不在目标 DTO 上，
 *       天然不下发。租户配置同理：只按需开单字段接口
 *       （{@code GET /tenant/config/default-im-agent}），<b>不做</b>「按配置名读任意配置项」的
 *       通用口子——那会让 {@code oaAppSecret} 一类敏感配置顺着同一个密钥面漏出去。</li>
 * </ul>
 * 放在 bootstrap 模块（同 HealthController）：这里是唯一同时可见 system 与 agent 各模块
 * Bean 的聚合点，且内部 API 的拦截器/豁免配置也都落在 bootstrap。
 * <p>
 * 新增子路径注意：需同步登记 application.yml 的 auth.excludePath（豁免登录），
 * 否则会被 AuthInterceptor 要求登录态。
 */
@Tag(name = "Internal API", description = "内部服务间 HTTP API（X-Api-Key 静态密钥认证，按租户只读查询）")
@RestController
@RequestMapping("/api/internal")
@Slf4j
public class InternalApiController {

    /**
     * 批量查询单次上限，防止超大 IN 列表拖垮数据库
     */
    private static final int MAX_BATCH_SIZE = 1000;

    /**
     * 搜索接口默认每页条数
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 搜索接口每页条数上限，防止调用方一次拉全量
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 空间名称长度上限，与 space.name 的 varchar(32) 对齐
     */
    private static final int SPACE_NAME_MAX_LENGTH = 32;

    /**
     * 可见性判定的单次上限，比通用批量查询（{@link #MAX_BATCH_SIZE}）小得多：
     * 判定要按「同一个智能体可能有 N 条发布记录」逐页扫，放开成百上千个 id 就成了放大攻击面
     */
    private static final int MAX_VISIBILITY_BATCH_SIZE = 50;

    /**
     * 可见性判定的翻页上限（每页 {@link #MAX_PAGE_SIZE} 行，最多 500 行）。
     * <p>
     * 为什么要翻页：发布表的行是「发布记录」不是「智能体」，同一个智能体发布到多个空间就是多行，
     * 而域层按 targetId 去重是<b>按页</b>做的——只用一页，可能被重复行挤掉后面的 id，
     * 表现成「明明可见却判成不可见」。正常情况（每个 id 1~2 行）一页就够，翻页只在超限时才发生；
     * 上限兜底是为了「一个 id 挂着极多条发布记录」时也不会无限翻下去。
     */
    private static final int MAX_VISIBILITY_PAGES = 5;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private IAgentRpcService agentRpcService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private VirtualIdentityApplicationService virtualIdentityApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ConversationDomainService conversationDomainService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    /**
     * 容器服务客户端。这里刻意<b>绕过</b> {@code ComputerPodApplicationService}：那一层从
     * {@link RequestContext} 取当前用户，而内部接口没有登录态、userId 由调用方显式声明，
     * 在内部接口里「扮演」用户只会掩盖「调用方声明的 userId 与实际执行身份不一致」这类问题。
     */
    @Resource
    private ComputerPodClient computerPodClient;

    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @Operation(summary = "按租户 + 用户ID/用户名/手机号查询用户详情（优先级 userId > userName > phone；"
            + "未找到或不属于该租户时 data 为 null）")
    @GetMapping("/user/detail")
    public ReqResult<UserDetailDto> getUserDetail(@RequestParam(value = "tenantId", required = false) Long tenantId,
                                                  @RequestParam(value = "userId", required = false) Long userId,
                                                  @RequestParam(value = "userName", required = false) String userName,
                                                  @RequestParam(value = "phone", required = false) String phone) {
        try {
            TenantConfigDto tenantConfigDto = applyTenant(tenantId);
            UserDto user;
            if (userId != null) {
                user = userApplicationService.queryById(userId);
            } else if (StringUtils.isNotBlank(userName)) {
                user = userApplicationService.queryUserByUserName(userName.trim());
            } else if (StringUtils.isNotBlank(phone)) {
                user = userApplicationService.queryUserByPhone(phone.trim());
            } else {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "userId、userName、phone 至少提供一个", "At least one of userId, userName or phone is required.");
            }
            if (user != null && !sameTenant(tenantId, user.getTenantId())) {
                // 用户域的底层查询豁免租户过滤（登录链路需要），这里必须自己收口，否则会跨租户泄露
                log.warn("Internal query user detail cross-tenant rejected, tenantId={}, hitTenantId={}, userId={}, "
                        + "userName={}, phone={}", tenantId, user.getTenantId(), userId, userName, phone);
                user = null;
            }
            log.info("Internal query user detail, tenantId={}, userId={}, userName={}, phone={}, hit={}",
                    tenantId, userId, userName, phone, user != null);
            resetUserAvatarUrl(tenantConfigDto, user);
            return ReqResult.success(toDetailDto(user));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query user detail failed, tenantId={}, userId={}", tenantId, userId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    private void resetUserAvatarUrl(TenantConfigDto tenantConfigDto, UserDto user) {
        if (tenantConfigDto == null) {
            return;
        }
        String siteUrl = tenantConfigDto.getSiteUrl();
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        user.setAvatar(siteUrl + "/api/user/avatar/" + user.getId() + (user.getAvatar() != null ? "/" + MD5.MD5Encode(user.getAvatar()) : ""));
    }

    @Operation(summary = "按租户 + 用户ID列表批量查询用户（body 为 JSON 数组，单次上限 1000；"
            + "不存在或不属于该租户的 id 直接跳过）")
    @PostMapping("/user/list")
    public ReqResult<List<UserDetailDto>> listUsers(@RequestParam(value = "tenantId", required = false) Long tenantId,
                                                    @RequestBody(required = false) List<Long> userIds) {
        try {
            TenantConfigDto tenantConfigDto = applyTenant(tenantId);
            List<Long> ids = normalizeIds(userIds);
            if (ids.isEmpty()) {
                return ReqResult.success(List.of());
            }
            List<UserDto> users = userApplicationService.queryUserListByIds(ids);
            List<UserDetailDto> result = new ArrayList<>();
            int crossTenant = 0;
            if (CollectionUtils.isNotEmpty(users)) {
                for (UserDto user : users) {
                    // 批量查用户底层同样豁免租户过滤（addTenantIgnoreEntity），逐行收口
                    if (!sameTenant(tenantId, user.getTenantId())) {
                        crossTenant++;
                        continue;
                    }
                    resetUserAvatarUrl(tenantConfigDto, user);
                    result.add(toDetailDto(user));
                }
            }
            if (crossTenant > 0) {
                log.warn("Internal query user list cross-tenant rows skipped, tenantId={}, skipped={}", tenantId, crossTenant);
            }
            log.info("Internal query user list, tenantId={}, requested={}, hit={}", tenantId, ids.size(), result.size());
            return ReqResult.success(result);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query user list failed, tenantId={}", tenantId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "按租户 + 智能体ID查询智能体基础信息（未找到或不属于该租户时 data 为 null）")
    @GetMapping("/agent/detail")
    public ReqResult<AgentInfoDto> getAgentDetail(@RequestParam(value = "tenantId", required = false) Long tenantId,
                                                  @RequestParam(value = "agentId", required = false) Long agentId) {
        try {
            applyTenant(tenantId);
            if (agentId == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "agentId");
            }
            List<AgentInfoDto> agents = queryAgentInfos(tenantId, List.of(agentId));
            AgentInfoDto agent = agents.isEmpty() ? null : agents.get(0);
            log.info("Internal query agent detail, tenantId={}, agentId={}, hit={}", tenantId, agentId, agent != null);
            return ReqResult.success(agent);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query agent detail failed, tenantId={}, agentId={}", tenantId, agentId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "按租户 + 智能体ID列表批量查询智能体基础信息（body 为 JSON 数组，单次上限 1000；"
            + "不存在或不属于该租户的 id 直接跳过）")
    @PostMapping("/agent/list")
    public ReqResult<List<AgentInfoDto>> listAgents(@RequestParam(value = "tenantId", required = false) Long tenantId,
                                                    @RequestBody(required = false) List<Long> agentIds) {
        try {
            applyTenant(tenantId);
            List<Long> ids = normalizeIds(agentIds);
            if (ids.isEmpty()) {
                return ReqResult.success(List.of());
            }
            List<AgentInfoDto> agents = queryAgentInfos(tenantId, ids);
            log.info("Internal query agent list, tenantId={}, requested={}, hit={}", tenantId, ids.size(), agents.size());
            return ReqResult.success(agents);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query agent list failed, tenantId={}", tenantId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "按租户 + 用户ID查询用户所属空间列表（含该用户在各空间中的角色 currentUserRole）")
    @GetMapping("/user/spaces")
    public ReqResult<List<SpaceDto>> listUserSpaces(@RequestParam(value = "tenantId", required = false) Long tenantId,
                                                    @RequestParam(value = "userId", required = false) Long userId) {
        try {
            applyTenant(tenantId);
            if (userId == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId");
            }
            // 只返回平台常规空间列表可见的类型（Personal / Team）：私聊（DM）与群组（Group）不在本接口下发
            List<SpaceDto> spaces = spaceApplicationService.queryVisibleListByUserId(userId);
            List<SpaceDto> result = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(spaces)) {
                for (SpaceDto space : spaces) {
                    // 空间查询本身已按租户过滤，这里再收口一次（同租户才下发）
                    if (sameTenant(tenantId, space.getTenantId())) {
                        result.add(space);
                    }
                }
            }
            log.info("Internal query user spaces, tenantId={}, userId={}, hit={}", tenantId, userId, result.size());
            return ReqResult.success(result);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query user spaces failed, tenantId={}, userId={}", tenantId, userId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 消息会话默认智能体配置（租户配置项 {@code defaultIMAgentConfig}），按约定的数组结构解析后下发。
     * <p>
     * 只暴露这一个配置项、不做通用配置读取（理由见 {@link InternalIMAgentConfigItemDto}）。
     * 结构由调用方约定（{@code [{"agentId":123,"defaultMessage":""}]}），平台负责解析与收口：
     * 空值 = 未配置，回空数组；<b>格式不合法则报 4000，不回空数组</b>——两者一旦混同，
     * 调用方会把「配置写坏了」当成「没配默认智能体」，而前者恰恰是需要被叫出来的一类故障。
     * 数组里缺 {@code agentId} 的条目丢弃（无 agentId 无法落地），每个租户的配置值形态各异，
     * 与其让调用方各自判空，不如在这里一次收口。
     * <p>
     * 值按 {@code tenantId} 现取（走页面端同一份 Redis 缓存 {@code tenant_config:{tenantId}}，
     * 配置改动由 {@code TenantConfigApplicationServiceImpl#updateConfig} 失效），
     * <b>不读</b> {@link RequestContext} 上挂的那份：{@code applyTenant} 在取不到配置时会保留
     * AuthInterceptor 按默认租户装载的那份（tenantId=1），照读会把别的租户的配置下发出去。
     * <p>
     * {@code userId} 可选：给了就把该用户名下<b>未在线</b>的个人沙盒（{@code scope=user}）也拼进结果，
     * 让客户端能把这些「个人电脑」当作可选的消息会话入口一起呈现；不给（或为 null）就只回租户配置那一份，
     * 与加这个参数之前的行为一致 —— 存量调用方不必改。
     */
    @Operation(summary = "按租户查询消息会话默认智能体配置（data 为 [{\"agentId\":123,\"defaultMessage\":\"\"}] 数组；"
            + "未配置时为空数组，配置不是合法数组时 code=4000；传 userId 时额外拼入该用户未在线的个人沙盒）")
    @GetMapping("/tenant/config/default-im-agent")
    public ReqResult<List<InternalIMAgentConfigItemDto>> getDefaultIMAgentConfig(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            applyTenant(tenantId);
            List<InternalIMAgentConfigItemDto> items = new ArrayList<>();
            if (userId != null) {
                List<SandboxConfigRpcDto> sandboxConfigRpcDtos = iSandboxConfigRpcService.queryUserConfigs(userId);
                // ★只读遍历，绝不修改返回的列表对象。空集时 SandboxConfigRpcServiceImpl#convertToRpcDtoList
                // 返回 List.of()（不可变），而 List.of() 的 removeIf 在 Java 9+ 被 ImmutableCollections 覆写成
                // 无条件抛 UnsupportedOperationException —— 与列表是否为空、谓词是否命中都无关。原先那行
                // removeIf 因此让「该用户名下没有沙盒」（最常见的入参）必然变成 0001 + 空 message（UOE 无消息），
                // 连租户配置那份也一起拿不到。顺带把 RPC 契约的空值一并兜住（接口未承诺非空、元素非 null）。
                if (sandboxConfigRpcDtos != null) {
                    for (SandboxConfigRpcDto sandboxConfigRpcDto : sandboxConfigRpcDtos) {
                        if (sandboxConfigRpcDto == null || !sandboxConfigRpcDto.isOnline()) {
                            continue;
                        }
                        InternalIMAgentConfigItemDto internalIMAgentConfigItemDto = new InternalIMAgentConfigItemDto();
                        internalIMAgentConfigItemDto.setAgentId(sandboxConfigRpcDto.getAgentId());
                        internalIMAgentConfigItemDto.setDefaultMessage("嘿，我是你的个人助理，住在「" + sandboxConfigRpcDto.getName() + "」");
                        items.add(internalIMAgentConfigItemDto);
                    }
                }
            }
            TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
            List<InternalIMAgentConfigItemDto> items1 = parseIMAgentConfig(
                    tenantConfig == null ? null : tenantConfig.getDefaultIMAgentConfig());
            if (items1 != null) {
                items.addAll(items1);
            }
            log.info("Internal query default im agent config, tenantId={}, userId={}, items={}",
                    tenantId, userId, items.size());
            return ReqResult.success(items);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query default im agent config failed, tenantId={}, userId={}", tenantId, userId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 配置值 → 条目列表（结构见 {@link InternalIMAgentConfigItemDto}）。
     * <p>
     * 空值/空白 = 未配置，回空列表；解析失败抛 4000（本控制器参数/配置类失败的统一码，原因只在 message 里），
     * <b>不</b>吞成空列表——那会让「配置写坏了」与「没配」在调用方眼里一模一样。
     * 其余条目按原样下发，不按 agentId 去重：重复项是配置本身的问题，平台不替它做决定，
     * 只保证每项可直接使用。
     */
    private static List<InternalIMAgentConfigItemDto> parseIMAgentConfig(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        List<InternalIMAgentConfigItemDto> parsed;
        try {
            Object json = JSON.parse(value.trim());
            // 先自己判形状：fastjson2 的 parseArray 遇到对象输入并不报错，会返回「元素其实是原始字符串」
            // 的列表，直接当 DTO 用才在遍历时炸成 ClassCastException（落到 0001，与「配置坏了」对不上）
            parsed = json instanceof JSONArray array ? array.toJavaList(InternalIMAgentConfigItemDto.class) : null;
        } catch (Exception e) {
            log.warn("Internal default im agent config parse failed, value={}", value, e);
            throw invalidIMAgentConfig();
        }
        if (parsed == null) {
            log.warn("Internal default im agent config is not a json array, value={}", value);
            throw invalidIMAgentConfig();
        }
        if (CollectionUtils.isEmpty(parsed)) {
            return List.of();
        }
        List<InternalIMAgentConfigItemDto> result = new ArrayList<>(parsed.size());
        for (InternalIMAgentConfigItemDto item : parsed) {
            if (item == null || item.getAgentId() == null) {
                log.warn("Internal default im agent config item skipped: no agentId, value={}", value);
                continue;
            }
            if (item.getDefaultMessage() == null) {
                item.setDefaultMessage(StringUtils.EMPTY);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 配置格式不合法：走本控制器参数/配置类失败的统一码（4000），原因只在 message 里
     */
    private static BizException invalidIMAgentConfig() {
        return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                "配置项 defaultIMAgentConfig 不是合法的 JSON 数组",
                "Config defaultIMAgentConfig is not a valid JSON array.");
    }

    @Operation(summary = "按租户 + 用户ID搜索已发布智能体（official=true 只搜系统推荐；"
            + "默认搜该用户全部空间 + 租户系统广场；访问受控的智能体按该用户数据权限过滤；"
            + "结果不含网页应用 PageApp）")
    @PostMapping("/agent/search")
    public ReqResult<InternalPageDto<InternalAgentSearchItemDto>> searchAgents(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalAgentSearchRequest request) {
        long page = 1L;
        long pageSize = DEFAULT_PAGE_SIZE;
        try {
            applyTenant(tenantId);
            InternalAgentSearchRequest req = request == null ? new InternalAgentSearchRequest() : request;
            page = normalizePage(req.getPage());
            pageSize = normalizePageSize(req.getPageSize());
            if (req.getUserId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId");
            }
            // 用户域底层豁免租户过滤（登录链路依赖），必须自己收口，否则会按别租户的 userId 搜出内容
            UserDto user = userApplicationService.queryById(req.getUserId());
            if (user == null || !sameTenant(tenantId, user.getTenantId())) {
                log.warn("Internal agent search user cross-tenant rejected, tenantId={}, userId={}, hitTenantId={}",
                        tenantId, req.getUserId(), user == null ? null : user.getTenantId());
                return ReqResult.success(InternalPageDto.empty(page, pageSize));
            }
            Long userId = user.getId();
            boolean official = Boolean.TRUE.equals(req.getOfficial());
            boolean onlySpace = Boolean.TRUE.equals(req.getOnlySpace());
            List<Long> authSpaceIds = queryAuthSpaceIds(tenantId, userId);

            PublishedQueryDto query = new PublishedQueryDto();
            query.setTargetType(Published.TargetType.Agent);
            query.setPage((int) page);
            query.setPageSize((int) pageSize);
            query.setKw(StringUtils.trimToNull(req.getKw()));
            query.setCategory(StringUtils.trimToNull(req.getCategory()));
            // 广场首屏的「推荐置顶」是前端展示语义，内部搜索不需要：关掉可避免其排除/追加逻辑干扰分页
            query.setShowRecommend(false);
            // 网页应用（Agent 下的 PageApp 子类型）不是智能体，不下发给内部调用方。
            // 必须在查询上排除而不是查回来再筛：分页与 total 由数据库算，内存过滤会让 total 虚高、翻页错位
            query.setExcludeTargetSubTypes(List.of(Published.TargetSubType.PageApp));
            if (official) {
                query.setOfficial(true);
                query.setTargetIds(officialAgentIds());
            } else if (req.getSpaceId() != null) {
                if (!authSpaceIds.contains(req.getSpaceId())) {
                    log.warn("Internal agent search space rejected, tenantId={}, userId={}, spaceId={}",
                            tenantId, userId, req.getSpaceId());
                    return ReqResult.success(InternalPageDto.empty(page, pageSize));
                }
                query.setSpaceId(req.getSpaceId());
            } else {
                if (onlySpace && authSpaceIds.isEmpty()) {
                    // 只搜空间内，但该用户没有任何空间。必须在这里返回，不能把空 spaceIds 传下去：
                    // 域层对空 spaceIds 会退化成「查租户系统广场」，与本参数语义相反
                    return ReqResult.success(InternalPageDto.empty(page, pageSize));
                }
                query.setSpaceIds(authSpaceIds);
            }
            if (onlySpace) {
                query.setJustReturnSpaceData(true);
            }

            SuperPage<PublishedDto> pageResult = publishApplicationService.queryPublishedList(query);
            List<InternalAgentSearchItemDto> records = toSearchItems(pageResult.getRecords());
            filterByDataPermission(records, user, userId);
            log.info("Internal agent search, tenantId={}, userId={}, official={}, spaceId={}, onlySpace={}, kw={}, "
                            + "page={}, pageSize={}, total={}, returned={}", tenantId, userId, official, req.getSpaceId(),
                    onlySpace, req.getKw(), page, pageSize, pageResult.getTotal(), records.size());
            return ReqResult.success(InternalPageDto.of(pageResult.getTotal(), page, pageSize, records));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal agent search failed, tenantId={}, page={}", tenantId, page, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 批量判定「这些智能体，这个用户能不能用」。
     * <p>
     * <b>可见口径与 {@code POST /agent/search} 完全同款</b>——同一个用户视角（该用户所属空间 +
     * 租户系统广场）、同一条数据权限收口（{@code accessControl=1} 的智能体只对授权的用户可见，
     * 管理员不受限，取不到权限数据按无权限处理），区别只是结果集被限制在调用方给定的 agentId 上、
     * 且只回「哪些可见」不回明细。这样调用方拿到的答案，与这个用户自己去广场搜一遍得到的答案一致；
     * 三条否定路径都不出现在结果里：<b>未发布</b>（压根不是可用状态）、<b>发布到了他不可见的空间</b>
     * （空间广场发布且他不是该空间成员）、<b>受管控且未授权</b>。
     * <p>
     * <b>为什么用户不存在/跨租户是报错而不是空结果</b>：本接口是「判定」，空结果的含义是
     * 「这些智能体一个都不可见」。参数错了却回空结果，调用方会把「视角给错了」读成「他什么都看不到」，
     * 排障时两边都冤枉。所以与空间/会话那几个代用户操作接口同款：userId 必须明确属于声明的租户
     * （用户域的底层查询刻意豁免租户过滤，这条必须自己收口，不能依赖 SQL）。
     * <p>
     * 只读，不产生任何模型调用与费用；{@code agentIds} 为空回空结果（不是错误，同
     * {@code POST /agent/list} 对空入参的处理）。
     */
    @Operation(summary = "按租户 + 用户ID 批量判定智能体可见性（口径同 /agent/search：该用户所属空间 + "
            + "租户系统广场，再按该用户数据权限收口；未发布 / 不可见空间 / 无数据权限的一律不出现在结果里）")
    @PostMapping("/agent/visibility/check")
    public ReqResult<InternalAgentVisibilityCheckDto> checkAgentVisibility(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalAgentVisibilityCheckRequest request) {
        Long userId = request == null ? null : request.getUserId();
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            // 纯内存校验（上限）先做：不合法就不必去读用户/发布表，调用方也能稳定拿到 50 这条上限
            List<Long> agentIds = normalizeIds(request.getAgentIds(), MAX_VISIBILITY_BATCH_SIZE);
            UserDto user = requireTenantUser(tenantId, userId, "userId");
            userId = user.getId();
            if (agentIds.isEmpty()) {
                log.info("Internal agent visibility check, tenantId={}, userId={}, requested=0, visible=0", tenantId,
                        userId);
                return ReqResult.success(toVisibilityDto(List.of()));
            }
            List<Long> visibleAgentIds = queryVisibleAgentIds(tenantId, userId, user, agentIds);
            log.info("Internal agent visibility check, tenantId={}, userId={}, requested={}, visible={}", tenantId,
                    userId, agentIds.size(), visibleAgentIds.size());
            return ReqResult.success(toVisibilityDto(visibleAgentIds));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal agent visibility check failed, tenantId={}, userId={}", tenantId, userId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "创建（或复用）虚拟身份，返回身份用户 id（accountType=agent 智能体身份 / group 群代理身份）；"
            + "按「租户 + 类型 + principalRefId」幂等，重复调用返回同一 id，不做更新或删除")
    @PostMapping("/user/virtual-identity/create")
    public ReqResult<InternalVirtualIdentityDto> createVirtualIdentity(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalVirtualIdentityCreateRequest request) {
        try {
            applyTenant(tenantId);
            InternalVirtualIdentityCreateRequest req = request == null ? new InternalVirtualIdentityCreateRequest()
                    : request;
            VirtualIdentityDto identity = virtualIdentityApplicationService.getOrCreateVirtualIdentity(tenantId,
                    req.getAccountType(), req.getPrincipalRefId(), req.getNickName(), req.getAvatar());
            log.info("Internal create virtual identity, tenantId={}, accountType={}, principalRefId={}, userId={}, "
                            + "newlyCreated={}", tenantId, req.getAccountType(), req.getPrincipalRefId(), identity.getId(),
                    identity.getNewlyCreated());
            return ReqResult.success(toIdentityDto(identity));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal create virtual identity failed, tenantId={}, accountType={}, principalRefId={}",
                    tenantId, request == null ? null : request.getAccountType(),
                    request == null ? null : request.getPrincipalRefId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * VirtualIdentityDto → 内部 API 契约。status 源为枚举、目标为 String，显式转枚举名。
     */
    private static InternalVirtualIdentityDto toIdentityDto(VirtualIdentityDto identity) {
        if (identity == null) {
            return null;
        }
        InternalVirtualIdentityDto dto = new InternalVirtualIdentityDto();
        BeanUtils.copyProperties(identity, dto);
        dto.setUserId(identity.getId());
        dto.setStatus(identity.getStatus() == null ? null : identity.getStatus().name());
        return dto;
    }

    @Operation(summary = "查询指定空间下的成员列表（空间不存在或不属于该租户时返回 3026）")
    @GetMapping("/space/user/list")
    public ReqResult<List<InternalSpaceUserDto>> listSpaceUsers(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "spaceId", required = false) Long spaceId) {
        try {
            applyTenant(tenantId);
            if (spaceId == null || spaceId <= 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "spaceId");
            }
            // 成员行自带租户列、查询本身会被租户拦截器过滤，但那样「空间不存在」「空间属于别的租户」
            // 和「空间里没人」都表现为空列表，调用方无法区分；故先显式确认空间的租户归属
            SpaceDto space = spaceApplicationService.queryById(spaceId);
            if (space == null || !sameTenant(tenantId, space.getTenantId())) {
                log.warn("Internal space member query rejected, tenantId={}, spaceId={}, hitTenantId={}", tenantId,
                        spaceId, space == null ? null : space.getTenantId());
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSpaceNotFound);
            }
            List<SpaceUserDto> members = spaceApplicationService.querySpaceUserList(spaceId);
            List<InternalSpaceUserDto> result = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(members)) {
                for (SpaceUserDto member : members) {
                    result.add(toSpaceUserDto(member));
                }
            }
            log.info("Internal query space members, tenantId={}, spaceId={}, hit={}", tenantId, spaceId, result.size());
            return ReqResult.success(result);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal query space members failed, tenantId={}, spaceId={}", tenantId, spaceId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 按租户 + 空间ID + 用户ID 添加空间成员。
     * <p>
     * <b>幂等</b>：调用方无需先查成员列表，重复调用不会产生第二条成员行。但要注意「幂等」的确切含义——
     * 已是成员时底层直接返回、<b>不会用本次传入的 role 覆盖原角色</b>，所以响应里的 role 是该成员
     * <b>当前的真实角色</b>，调用方据它判断是「本次新增」还是「本来就已是成员（且角色与期望不同）」。
     * 本接口不提供角色变更能力：那需要空间管理端的角色更新接口。
     */
    @Operation(summary = "按租户 + 空间ID + 用户ID 添加空间成员（幂等；已是成员时不改角色，响应返回其当前真实角色）")
    @PostMapping("/space/user/add")
    public ReqResult<InternalSpaceUserDto> addSpaceUser(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalSpaceMemberRequest request) {
        Long spaceId = request == null ? null : request.getSpaceId();
        Long userId = request == null ? null : request.getUserId();
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            UserDto user = requireTenantUser(tenantId, request.getUserId(), "userId");
            userId = user.getId();
            SpaceDto space = requireTenantSpace(tenantId, request.getSpaceId(), "spaceId");
            spaceId = space.getId();
            // 个人空间的成员就是空间所有者本人，不接受成员增删。页面端 /api/space/user/add 对 Personal 是
            // 静默跳过，这里改为显式报错——内部调用若也静默，调用方会以为加成功了
            if (space.getType() == Space.Type.Personal) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.systemPersonalSpaceMemberForbidden);
            }
            SpaceUser.Role role = parseMemberRole(request.getRole());
            SpaceUserDto toAdd = new SpaceUserDto();
            toAdd.setSpaceId(space.getId());
            toAdd.setUserId(user.getId());
            toAdd.setRole(role);
            spaceApplicationService.addSpaceUser(toAdd);
            SpaceUserDto current = spaceApplicationService.querySpaceUser(space.getId(), user.getId());
            log.info("Internal add space user, tenantId={}, spaceId={}, userId={}, requestedRole={}, actualRole={}",
                    tenantId, space.getId(), user.getId(), role, current == null ? null : current.getRole());
            return ReqResult.success(current == null ? null : toSpaceUserDto(current, user));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal add space user failed, tenantId={}, spaceId={}, userId={}", tenantId, spaceId, userId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 按租户 + 空间ID + 用户ID 移除空间成员。
     * <p>
     * 响应的 data 表示「是否确实删掉了成员行」：false 表示该用户本来就不是成员（幂等成功，不是错误）。
     * 空间创建者不可移除（3027）——否则空间会变成无主；要转移所有权请走页面端的空间转让接口。
     */
    @Operation(summary = "按租户 + 空间ID + 用户ID 移除空间成员（幂等；data=false 表示本来就不是成员；禁止移除创建者）")
    @PostMapping("/space/user/delete")
    public ReqResult<Boolean> deleteSpaceUser(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalSpaceMemberRequest request) {
        Long spaceId = request == null ? null : request.getSpaceId();
        Long userId = request == null ? null : request.getUserId();
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            SpaceDto space = requireTenantSpace(tenantId, request.getSpaceId(), "spaceId");
            spaceId = space.getId();
            UserDto user = requireTenantUser(tenantId, request.getUserId(), "userId");
            userId = user.getId();
            if (space.getType() == Space.Type.Personal) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.systemPersonalSpaceMemberForbidden);
            }
            if (user.getId().equals(space.getCreatorId())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.systemCannotDeleteSpaceCreator);
            }
            boolean wasMember = spaceApplicationService.querySpaceUser(space.getId(), user.getId()) != null;
            spaceApplicationService.deleteSpaceUser(space.getId(), user.getId());
            log.info("Internal delete space user, tenantId={}, spaceId={}, userId={}, wasMember={}", tenantId,
                    space.getId(), user.getId(), wasMember);
            return ReqResult.success(wasMember);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal delete space user failed, tenantId={}, spaceId={}, userId={}", tenantId, spaceId, userId,
                    e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 按租户 + 创建者用户ID 创建空间（Group 群组 / DM 私聊）。
     * <p>
     * 四个刻意的边界：
     * <ol>
     *   <li><b>创建者必传</b>（{@code ownerUserId}）：{@code space.creator_id} 非空，且底层建空间后会把
     *       创建者写成 Owner 成员；内部接口没有登录态，只能由调用方指定。</li>
     *   <li><b>类型白名单只有 Group 与 DM</b>：Personal 是登录注册链路的产物，绝不能从内部接口凭空造；
     *       Team 受页面端「用户可创建团队空间数量」配额约束，从内部接口创建会绕过配额。</li>
     *   <li><b>不校验配额</b>：内部接口是平台侧管理动作，且配额统计只算 Team，Group / DM 本就不计入。</li>
     *   <li><b>DM 可带 {@code peerUserId}</b>：建空间后把「对方」写成成员（role=User，与
     *       {@code /space/user/add} 的默认角色一致）。该参数只对 DM 有效，Group 传了直接拒绝；
     *       成员数不设上限，与 Group 一致。</li>
     * </ol>
     */
    @Operation(summary = "按租户 + 创建者用户ID 创建空间（type 接受 Group 与 DM；创建者自动成为 Owner 成员；DM 可带 peerUserId 一并写入对方；不校验团队空间配额）")
    @PostMapping("/space/create")
    public ReqResult<InternalSpaceDto> createSpace(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalSpaceCreateRequest request) {
        Long ownerUserId = request == null ? null : request.getOwnerUserId();
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            if (StringUtils.isBlank(request.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "name");
            }
            // space.name 是 varchar(32)，超长会直接抛 SQL 异常（页面端同样没有长度校验），这里提前拦住
            if (request.getName().trim().length() > SPACE_NAME_MAX_LENGTH) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "空间名称长度不能超过 " + SPACE_NAME_MAX_LENGTH,
                        "Space name must not exceed " + SPACE_NAME_MAX_LENGTH + " characters.");
            }
            UserDto owner = requireTenantUser(tenantId, request.getOwnerUserId(), "ownerUserId");
            ownerUserId = owner.getId();
            Space.Type type = parseSpaceType(request.getType());
            Long peerUserId = resolvePeerUserId(tenantId, request.getPeerUserId(), type, owner.getId());
            SpaceDto spaceDto = SpaceDto.builder()
                    .name(request.getName().trim())
                    .description(request.getDescription())
                    .icon(request.getIcon())
                    .type(type)
                    .creatorId(owner.getId())
                    .build();
            Long spaceId = spaceApplicationService.add(spaceDto);
            if (peerUserId != null) {
                attachPeerMember(spaceId, peerUserId);
            }
            SpaceDto created = spaceApplicationService.queryById(spaceId);
            log.info("Internal create space, tenantId={}, spaceId={}, ownerUserId={}, type={}, peerUserId={}, name={}",
                    tenantId, spaceId, owner.getId(), type, peerUserId, request.getName());
            return ReqResult.success(toSpaceDto(created));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal create space failed, tenantId={}, ownerUserId={}", tenantId, ownerUserId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 按租户 + 空间ID 删除空间。
     * <p>
     * <b>当前只允许删除 Group（群组）与 DM（私聊）空间</b>（3038）：Personal 删不得（会毁掉用户的
     * 个人空间），Team/Class 是页面端管理的既有空间，删除权限仍留在页面端。
     * <p>
     * <b>不可逆</b>：底层是物理删除——删除 space 行与 space_user 行，并向 delete_space_queue 与
     * delete_space_repo_queue 两个 Redis 队列各推一条，由 agent 侧与 repo 侧异步清理该空间的发布物与
     * 文档。所以本接口返回成功只代表空间行已删，外围数据的清理是后续异步完成的。
     */
    @Operation(summary = "按租户 + 空间ID 删除空间（当前只允许 type=Group|DM；物理删除且不可逆，外围数据异步清理）")
    @PostMapping("/space/delete")
    public ReqResult<Void> deleteSpace(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "spaceId", required = false) Long spaceId) {
        try {
            applyTenant(tenantId);
            SpaceDto space = requireTenantSpace(tenantId, spaceId, "spaceId");
            if (!isInternalManagedType(space.getType())) {
                log.warn("Internal delete space rejected by type, tenantId={}, spaceId={}, type={}", tenantId,
                        space.getId(), space.getType());
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.systemSpaceTypeDeleteForbidden);
            }
            spaceApplicationService.delete(space.getId());
            log.info("Internal delete space, tenantId={}, spaceId={}, name={}", tenantId, space.getId(),
                    space.getName());
            return ReqResult.success();
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal delete space failed, tenantId={}, spaceId={}", tenantId, spaceId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "按租户 + 用户ID 创建智能体会话（代该用户建会话并做权限校验；只建空会话，不产生模型调用与费用）")
    @PostMapping("/agent/conversation/create")
    public ReqResult<InternalConversationDto> createConversation(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalConversationCreateRequest request) {
        Long userId = null;
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            UserDto user = requireTenantUser(tenantId, request.getUserId(), "userId");
            userId = user.getId();
            if (request.getAgentId() == null || request.getAgentId() <= 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "agentId");
            }
            final Long conversationUserId = user.getId();
            // 与 /api/agent/conversation/create 同构的权限校验，只是校验主体是「被代表的用户」
            InternalConversationDto dto = runAsUser(user, () -> {
                if (request.isDevMode()) {
                    AgentConfigDto agentConfigDto = agentApplicationService.queryById(request.getAgentId());
                    if (agentConfigDto == null) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
                    }
                    spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
                } else {
                    PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent,
                            request.getAgentId());
                    if (publishedDto == null) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOfflineOrNotFound);
                    }
                    //在添加的时候做了校验
//                    PublishedPermissionDto permission = publishApplicationService.hasPermission(
//                            Published.TargetType.Agent, request.getAgentId());
//                    if (permission == null || !permission.isExecute()) {
//                        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
//                    }
                }
                ConversationDto conversation = conversationApplicationService.createConversation(conversationUserId,
                        request.getAgentId(), request.isDevMode(), request.getVariables(), request.getSandboxId(),
                        request.getWorkspacePath(), request.getProjectId(), request.getProjectType());
                return toConversationDto(conversation);
            });
            log.info("Internal create conversation, tenantId={}, userId={}, agentId={}, devMode={}, conversationId={}",
                    tenantId, userId, request.getAgentId(), request.isDevMode(),
                    dto == null ? null : dto.getConversationId());
            return ReqResult.success(dto);
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal create conversation failed, tenantId={}, userId={}, agentId={}", tenantId, userId,
                    request == null ? null : request.getAgentId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 按租户 + 用户ID（+ 可选 billUserId）发起一轮智能体会话，SSE 流式透传。
     * <p>
     * 前置校验与上下文准备全部在 <b>返回 Flux 之前</b> 同步完成：参数错误、用户/会话归属错误一律抛
     * {@link BizException}，由 AppExceptionHandler 转成 HTTP 200 + JSON 的 {@link ReqResult}
     * （Content-Type 为 application/json，不是 SSE 帧）——调用方用错参数时应立刻拿到结构化错误，
     * 而不是先建立 SSE 连接、再在事件流里收一条错误。<b>连接一旦建立，后续错误都在流内以事件形式给出</b>。
     */
    @Operation(summary = "按租户 + 用户ID + billUserId 发起智能体会话（SSE 流式，逐事件透传；"
            + "参数/归属校验失败返回 JSON 而非 SSE 帧）")
    @PostMapping(value = "/agent/conversation/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> chatConversation(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalConversationChatRequest request,
            HttpServletResponse response) {
        applyTenant(tenantId);
        if (request == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "请求体不能为空", "Request body is required.");
        }
        if (request.getConversationId() == null || request.getConversationId() <= 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "conversationId");
        }
        if (StringUtils.isBlank(request.getMessage())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "message");
        }
        UserDto user = requireTenantUser(tenantId, request.getUserId(), "userId");
        // billUserId 只在这里校验归属；「是否真的生效」取决于该用户的订阅形态（包干订阅/试用时费用仍记
        // Agent 创建者），由计费链路自己判定，见 ChatApplicationServiceImpl#estimatePrice
        UserDto billUser = requireTenantUser(tenantId, request.getBillUserId(), "billUserId", true);
        // 会话必须属于该用户：getConversation 自带 userId 归属过滤，跨用户/跨租户都取不到
        ConversationDto conversation = conversationApplicationService.getConversation(user.getId(), request.getConversationId());
        if (conversation == null) {
            log.warn("Internal chat conversation not found, tenantId={}, userId={}, conversationId={}", tenantId,
                    user.getId(), request.getConversationId());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
        }
        // 将用户添加到聊天空间里，确保各类权限相互都有
        if (request.getVariableParams() != null && request.getVariableParams().containsKey("SPACE_ID")) {
            try {
                Long spaceId = Long.parseLong(request.getVariableParams().get("SPACE_ID").toString());
                SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, request.getUserId());
                if (spaceUserDto == null) {
                    SpaceUserDto spaceUser = new SpaceUserDto();
                    spaceUser.setSpaceId(spaceId);
                    spaceUser.setRole(SpaceUser.Role.User);
                    spaceUser.setUserId(request.getUserId());
                    spaceApplicationService.addSpaceUser(spaceUser);
                }
                if (conversation.getDevSpaceId() == null) {
                    Conversation conversationUpdate = new Conversation();
                    conversationUpdate.setDevSpaceId(spaceId);
                    conversationUpdate.setType(Conversation.ConversationType.IM);
                    conversationDomainService.updateConversation(request.getUserId(), conversation.getId(), conversationUpdate);
                }
            } catch (NumberFormatException e) {
                //
                log.warn("add group user to Space", e);
            }
        }
        final Long effectiveTenantId = tenantId;
        final Long billUserId = billUser == null ? null : billUser.getId();
        final InternalConversationChatRequest req = request;
        request.getVariableParams().put("BILL_USER_ID", billUserId);
        response.setCharacterEncoding("utf-8");
        log.info("Internal chat conversation, tenantId={}, userId={}, billUserId={}, conversationId={}, modelId={}",
                tenantId, user.getId(), billUserId, req.getConversationId(), req.getModelId());
        return Flux.defer(() -> {
            // SSE 在异步线程产出，而请求线程收尾时 AuthInterceptor#afterCompletion 已经把上下文清掉，
            // 所以必须在订阅线程重建整套上下文（tenantId / tenantConfig / 身份 / 付费方覆盖）。
            // 做法同 IM 渠道 WeworkAgentApplicationServiceImpl#executeAgentStream。
            RequestContext<Object> context = new RequestContext<>();
            context.setTenantId(effectiveTenantId);
            context.setTenantConfig(tenantConfigApplicationService.getTenantConfig(effectiveTenantId));
            RequestContext.set(context);
            UserDto chatUser = userApplicationService.queryById(user.getId());
            if (chatUser == null || !sameTenant(effectiveTenantId, chatUser.getTenantId())) {
                // 请求线程已校验过，这里是流开始前最后一次兜底：用户被删或改了租户就直接终止
                log.warn("Internal chat user vanished, tenantId={}, userId={}", effectiveTenantId, user.getId());
                RequestContext.remove();
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemUserNotFound);
            }
            context.setUser(chatUser);
            context.setUserId(chatUser.getId());
            context.setBillUserId(billUserId);
            context.setFrom(NUWAX_IM.getCode());
            TryReqDto tryReqDto = new TryReqDto();
            tryReqDto.setConversationId(req.getConversationId());
            tryReqDto.setMessage(req.getMessage());
            tryReqDto.setImVars(req.getVariableParams());
            tryReqDto.setAttachments(req.getAttachments());
            tryReqDto.setModelId(req.getModelId());
            tryReqDto.setSkillIds(req.getSkillIds());
            // from 固定为内部调用标识：TryReqDto.from 只对 blank/"chat"/"MasterAgent" 触发「最近使用」与
            // 主题更新（ChatApplicationServiceImpl 内），内部代发起不应污染用户的最近使用列表
            tryReqDto.setFrom(NUWAX_IM.getCode());
            tryReqDto.setFilterSensitive(null);
            tryReqDto.setAgentConfig(null);
            return chatApplicationService.chat(tryReqDto, new HashMap<>(), false)
                    .doFinally(signal -> RequestContext.remove());
        });
    }

    /**
     * 按租户 + 用户ID + 会话ID 重启智能体电脑（销毁容器后重建）。
     * <p>
     * <b>为什么要有它</b>：页面端的 {@code POST /api/computer/pod/restart} 判权限用的是「会话属于当前
     * 登录用户」，内部调用没有登录态，复用不了，于是这里把「是谁的电脑」改成由调用方显式声明，并自行校验。
     * <p>
     * <b>两道归属校验，缺一不可</b>：
     * <ol>
     *   <li>userId 必须属于声明的租户（{@link #requireTenantUser}）。用户域的底层查询刻意豁免租户过滤
     *       （登录链路要按 userId 跨租户查人），所以不能指望 SQL 兜底。</li>
     *   <li>会话必须属于这个 userId——走 {@code getConversation(userId, id)}，它自带 userId 过滤，
     *       跨用户/跨租户一律取不到。少了这一条，任何拿着内部密钥的调用方都能用一个 userId 去重启
     *       <b>别人</b>的容器：容器是按 project_id(userId) + cId 定位的，容器服务不会替我们再判一次归属。</li>
     * </ol>
     * 但<b>调用方仍对 userId 的正确性负全责</b>：本接口只证明「这台电脑是这个人的」，不判断「调用方
     * 是否有权代表这个人」——那是调用方业务侧的职责（同 {@code /agent/conversation/create} 的口径）。
     * <p>
     * <b>破坏性</b>：重启 = 销毁容器后重建，容器内未持久化的运行态（进行中的进程、内存态、非挂载目录下的
     * 临时文件）会丢；工作区与持久化目录不受影响。接口<b>不做二次确认、也没有幂等语义</b>——调用两次就是
     * 重启两次（底层 {@code userId_cId_restart} 那个串只是日志标识，不是发给下游的幂等键），去重由调用方自己做。
     * <p>
     * <b>响应口径</b>：外层信封的 {@code code} <b>不是平台统一业务码，而是容器服务返回的 code</b>
     * （{@code 0000} 成功），与页面端 {@code /api/computer/pod/restart} 完全一致——容器操作的成败判定在
     * 容器服务手里，这里透传而不二次翻译，避免「平台说成功、容器其实没重启」。{@code data} 是容器服务返回体里的
     * {@code data}（原样透传）。参数错误与归属校验失败走本控制器统一的失败口径：{@code code=4000}
     * （{@link ErrorCodeEnum#INVALID_PARAM}），<b>具体原因只在 message 里</b>（如「参数'tenantId'不能为空」
     * 「用户不存在」「会话不存在」，按请求语言给文案）——这三种拒绝共用一个 code，调用方要区分就得读 message，
     * 别只看 code。容器服务 4xx 且错误体里没带 code、响应体为空、5xx/连不上这三种情况，统一收敛为
     * {@code 9309 容器服务调用失败}（{@link BizExceptionCodeEnum#agentContainerServiceCallFailed}）——
     * 不能把 {@code code=null} 透传给调用方，那会被读成「没分类」。
     */
    @Operation(summary = "按租户 + 用户ID + 会话ID 重启智能体电脑（销毁容器后重建；"
            + "响应 code 为容器服务的码，0000 成功）")
    @PostMapping("/agent/computer/restart")
    public ReqResult<Object> restartComputer(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestBody(required = false) InternalComputerRestartRequest request) {
        Long conversationId = request == null ? null : request.getConversationId();
        Long userId = request == null ? null : request.getUserId();
        try {
            applyTenant(tenantId);
            if (request == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "请求体不能为空", "Request body is required.");
            }
            if (conversationId == null || conversationId <= 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                        "conversationId");
            }
            UserDto user = requireTenantUser(tenantId, request.getUserId(), "userId");
            userId = user.getId();
            // 会话必须属于该用户：getConversation 自带 userId 归属过滤，跨用户/跨租户都取不到
            if (conversationApplicationService.getConversation(user.getId(), conversationId) == null) {
                log.warn("Internal restart computer conversation not found, tenantId={}, userId={}, conversationId={}",
                        tenantId, user.getId(), conversationId);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
            }
            log.info("Internal restart agent computer, tenantId={}, userId={}, conversationId={}, appStage={}",
                    tenantId, user.getId(), conversationId, request.getAppStage());
            Map<String, Object> result = computerPodClient.restart(conversationId, user.getId(), request.getAppStage());
            return toContainerResult(result, "restart", conversationId, user.getId());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (RestClientException e) {
            // ComputerPodClient 只吞 4xx（转成带 message 的 map），5xx / 连不上 / 超时都会原样抛上来。
            // 这些同样是「容器服务调用失败」，收敛成 9309 而不是让它落到下面的 0001——否则调用方只看到
            // 一串 Spring 异常文本，分不清是自己参数错了还是容器服务病了
            log.error("Internal restart agent computer container call failed, tenantId={}, userId={}, conversationId={}",
                    tenantId, userId, conversationId, e);
            return containerFailure(e.getMessage(), null);
        } catch (Exception e) {
            log.error("Internal restart agent computer failed, tenantId={}, userId={}, conversationId={}", tenantId,
                    userId, conversationId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 容器服务响应体 → 内部 API 响应信封。code/message/data 原样透传（口径同页面端
     * {@code ComputerPodController#buildResponse}），只有「响应体为空」与「4xx 且下游没给 code」两种
     * 情况按容器服务调用失败收敛——这两种情况下 {@code code} 会是 null，而调用方是按 code 分支的。
     */
    private static ReqResult<Object> toContainerResult(Map<String, Object> result, String action,
                                                       Long conversationId, Long userId) {
        if (result == null) {
            log.error("Internal {} container result is null, conversationId={}, userId={}", action, conversationId,
                    userId);
            return containerFailure(null, null);
        }
        Object rawCode = result.get("code");
        String code = rawCode == null ? null : String.valueOf(rawCode);
        Object rawMessage = result.get("message");
        String message = rawMessage == null ? null : String.valueOf(rawMessage);
        Object data = result.get("data");
        log.info("Internal {} container result, conversationId={}, userId={}, code={}, message={}, tid={}", action,
                conversationId, userId, code, message, result.get("tid"));
        if (StringUtils.isBlank(code)) {
            // ComputerPodClient#parseClientErr 只保证 message（下游错误体没 code 时它就是没有），
            // 此处补平台码，把下游原文留在 message 里，便于调用方与平台侧对齐同一条错误
            return containerFailure(message, data);
        }
        if (ReqResult.SUCCESS.equals(code)) {
            return ReqResult.create(code, message, data);
        }
        return ReqResult.create(code, code, message != null ? message : containerFailureMessage(), data);
    }

    /**
     * 容器服务调用失败的统一失败信封（9309）；{@code message} 为下游原文时优先用它
     */
    private static ReqResult<Object> containerFailure(String message, Object data) {
        String code = BizExceptionCodeEnum.agentContainerServiceCallFailed.getCode();
        return ReqResult.create(code, code, message != null ? message : containerFailureMessage(), data);
    }

    private static String containerFailureMessage() {
        return BizExceptionCodeEnum.agentContainerServiceCallFailed.pickMessageTemplateForRequest();
    }

    /**
     * 把请求上下文切到调用方显式声明的租户。
     * <p>
     * 这些路径豁免登录，AuthInterceptor 落的默认租户（域名解析/x-tenant-id 头/兜底 1）对
     * 内部调用没有意义，必须以入参为准覆盖；覆盖后所有下游 SQL 由 MP 租户拦截器限定在该
     * 租户内（fail-closed）——传了别租户的资源 id 只会查空，不会泄露。
     */
    private TenantConfigDto applyTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "tenantId");
        }
        var requestContext = RequestContext.get();
        requestContext.setTenantId(tenantId);
        // 站点配置（tenantConfig）是 AuthInterceptor 按「默认租户」装载的，豁免登录的路径上是 tenantId=1 那份。
        // 调用方声明了别的租户时必须一并刷新，否则会读到错租户的站点配置（如官方推荐 id、默认图标地址）
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
        if (tenantConfig != null) {
            tenantConfig.setTenantId(tenantId);
            requestContext.setTenantConfig(tenantConfig);
        }
        return tenantConfig;
    }

    /**
     * 该用户在指定租户下可见的空间 id 列表（内部接口自己补，调用方不需要传）。
     * 空间查询本身已按租户过滤，这里再显式收口一次——与其他接口同一纪律。
     */
    private List<Long> queryAuthSpaceIds(Long tenantId, Long userId) {
        List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userId);
        if (CollectionUtils.isEmpty(spaces)) {
            return List.of();
        }
        List<Long> spaceIds = new ArrayList<>(spaces.size());
        for (SpaceDto space : spaces) {
            if (sameTenant(tenantId, space.getTenantId())) {
                spaceIds.add(space.getId());
            }
        }
        return spaceIds;
    }

    /**
     * 系统推荐（官方标识）的智能体 id 集合。
     * <p>
     * 与 {@code PublishedController#completeOfficialTargetIds} 同源：优先取推荐位配置，
     * 为空再回退租户站点配置里的 officialAgentIds；两者都空时用不可能命中的 -1 占位，
     * 保证「无官方推荐」时返回空集而不是退化成全量。
     */
    private List<Long> officialAgentIds() {
        List<Long> recommendIds = recommendApplicationService
                .list(TargetRecommend.RecType.Official.name(), TargetRecommend.TargetType.Agent.name())
                .stream().map(TargetRecommendResponse::getTargetId).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(recommendIds)) {
            return recommendIds;
        }
        TenantConfigDto tenantConfig = RequestContext.get() == null ? null
                : (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && CollectionUtils.isNotEmpty(tenantConfig.getOfficialAgentIds())) {
            return tenantConfig.getOfficialAgentIds();
        }
        return List.of(-1L);
    }

    /**
     * 访问控制收口：{@code accessControl=1} 的智能体只有被数据权限授权的用户可见。
     * 与广场接口同一口径——管理员不受限；无权限数据的普通用户一律过滤；
     * <b>取不到权限数据时按无权限处理</b>（fail-closed，不外泄受控资源）。
     */
    private void filterByDataPermission(List<InternalAgentSearchItemDto> records, UserDto user, Long userId) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        if (user.getRole() == User.Role.Admin) {
            return;
        }
        UserDataPermissionDto permission = userDataPermissionRpcService.getUserDataPermission(userId);
        List<Long> agentIds = permission == null || permission.getAgentIds() == null ? List.of()
                : permission.getAgentIds();
        List<Long> pageAgentIds = permission == null || permission.getPageAgentIds() == null ? List.of()
                : permission.getPageAgentIds();
        records.removeIf(item -> item.getAccessControl() != null && item.getAccessControl() == 1
                && !agentIds.contains(item.getAgentId()) && !pageAgentIds.contains(item.getAgentId()));
    }

    /**
     * 求「请求的 agentIds 里，哪些对该用户可见」，返回顺序与请求一致（调用方通常要按自己的顺序回显）。
     * <p>
     * 复用 {@code /agent/search} 的整条链路而不是另写一套判据：同一条空间范围表达式（该用户所属空间
     * ∪ 租户系统广场，空空间列表时域层退化成只查租户系统广场）、同一个 DTO 转换、同一个
     * {@link #filterByDataPermission} 收口——口径只有一份，改了搜索口径这里自动跟着变，不会两边打架。
     * <p>
     * 翻页见 {@link #MAX_VISIBILITY_PAGES}：命中即记「已判定」，同页命中的 id 不再追，
     * 因此正常情况只查一次；「查完了但没命中」的 id 也留在已判定集合里，不会被下一页重复计入。
     */
    private List<Long> queryVisibleAgentIds(Long tenantId, Long userId, UserDto user, List<Long> agentIds) {
        List<Long> authSpaceIds = queryAuthSpaceIds(tenantId, userId);
        Set<Long> visibleAgentIds = new LinkedHashSet<>();
        Set<Long> resolvedAgentIds = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_VISIBILITY_PAGES && resolvedAgentIds.size() < agentIds.size(); page++) {
            PublishedQueryDto query = new PublishedQueryDto();
            query.setTargetType(Published.TargetType.Agent);
            query.setPage(page);
            query.setPageSize(MAX_PAGE_SIZE);
            // 同搜索接口：推荐置顶是前端展示语义，会把推荐智能体额外塞进结果并改分页，这里必须关掉
            query.setShowRecommend(false);
            query.setTargetIds(agentIds);
            query.setSpaceIds(authSpaceIds);
            SuperPage<PublishedDto> pageResult = publishApplicationService.queryPublishedList(query);
            List<PublishedDto> publishedList = pageResult.getRecords();
            if (CollectionUtils.isEmpty(publishedList)) {
                break;
            }
            List<InternalAgentSearchItemDto> records = toSearchItems(publishedList);
            // 先登记命中（本页出现的 id 都已有结论），再按数据权限收口后登记可见
            Set<Long> hitAgentIds = records.stream().map(InternalAgentSearchItemDto::getAgentId)
                    .collect(Collectors.toSet());
            filterByDataPermission(records, user, userId);
            records.forEach(item -> visibleAgentIds.add(item.getAgentId()));
            resolvedAgentIds.addAll(hitAgentIds);
            if (publishedList.size() < MAX_PAGE_SIZE) {
                break;
            }
        }
        return agentIds.stream().filter(visibleAgentIds::contains).collect(Collectors.toList());
    }

    /**
     * 可见 id 列表 → 内部 API 契约（单字段，包一层是为了后续加字段时不改调用方的反序列化结构）
     */
    private static InternalAgentVisibilityCheckDto toVisibilityDto(List<Long> visibleAgentIds) {
        InternalAgentVisibilityCheckDto dto = new InternalAgentVisibilityCheckDto();
        dto.setVisibleAgentIds(visibleAgentIds);
        return dto;
    }

    /**
     * 发布记录 → 内部搜索条目（只暴露发布态基础信息，见 DTO 注释）
     */
    private static List<InternalAgentSearchItemDto> toSearchItems(List<PublishedDto> publishedList) {
        if (CollectionUtils.isEmpty(publishedList)) {
            return new ArrayList<>();
        }
        List<InternalAgentSearchItemDto> items = new ArrayList<>(publishedList.size());
        for (PublishedDto published : publishedList) {
            InternalAgentSearchItemDto item = new InternalAgentSearchItemDto();
            item.setPublishedId(published.getId());
            item.setAgentId(published.getTargetId());
            item.setName(published.getName());
            item.setDescription(published.getDescription());
            item.setIcon(withDefaultIcon(published.getIcon(), published.getName()));
            item.setCategory(published.getCategory());
            boolean systemScope = published.getScope() == Published.PublishScope.Tenant;
            item.setSpaceId(systemScope ? null : published.getSpaceId());
            item.setScope(published.getScope() == null ? null : published.getScope().name());
            item.setTargetSubType(published.getTargetSubType() == null ? null : published.getTargetSubType().name());
            item.setAgentType(published.getAgentType());
            item.setUsageScenarios(published.getUsageScenarios());
            item.setAllowCopy(published.getAllowCopy());
            item.setAccessControl(published.getAccessControl());
            item.setCreated(published.getCreated());
            item.setModified(published.getModified());
            items.add(item);
        }
        return items;
    }

    /**
     * 补平台默认图标（同广场接口）。站点地址缺失时原样返回：DefaultIconUrlUtil 内部直接
     * 取 tenantConfig.getSiteUrl() 拼地址，不做判空，内部调用不该因站点配置缺失而整体失败。
     */
    private static String withDefaultIcon(String icon, String name) {
        TenantConfigDto tenantConfig = RequestContext.get() == null ? null
                : (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig == null || StringUtils.isBlank(tenantConfig.getSiteUrl())) {
            return icon;
        }
        return DefaultIconUrlUtil.setDefaultIconUrl(icon, name, "agent", true);
    }

    private static long normalizePage(Integer page) {
        return page == null || page <= 0 ? 1L : page;
    }

    private static long normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "每页条数超出上限 " + MAX_PAGE_SIZE, "Page size exceeds limit " + MAX_PAGE_SIZE + ".");
        }
        return pageSize;
    }

    /**
     * 批量 id 规范化：去 null、去重（保序）、上限校验
     */
    private static List<Long> normalizeIds(List<Long> ids) {
        return normalizeIds(ids, MAX_BATCH_SIZE);
    }

    /**
     * {@link #normalizeIds(List)} 的可指定上限版本：可见性判定这类「每个 id 都要多查一行」的接口
     * 上限比通用批量查询小得多，见 {@link #MAX_VISIBILITY_BATCH_SIZE}
     */
    private static List<Long> normalizeIds(List<Long> ids, int maxSize) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        if (set.size() > maxSize) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "批量查询数量超出上限 " + maxSize, "Batch size exceeds limit " + maxSize + ".");
        }
        return new ArrayList<>(set);
    }

    /**
     * 解包 agent 模块 RPC 的 ReqResult（注意与 spec 的 ReqResult 是两个同名类），
     * 并只保留属于调用方租户的行。智能体查询本身走 MP 租户过滤，这里再显式收口一次：
     * 不依赖底层 SQL 的过滤行为是内部 API 的既定纪律（用户域就存在刻意的豁免）。
     */
    private List<AgentInfoDto> queryAgentInfos(Long tenantId, List<Long> agentIds) {
        com.xspaceagi.agent.core.sdk.dto.ReqResult<List<AgentInfoDto>> rpc =
                agentRpcService.queryAgentInfoList(agentIds);
        if (rpc == null || !rpc.isSuccess()) {
            log.error("Agent rpc queryAgentInfoList failed, agentIds={}, code={}, message={}", agentIds,
                    rpc == null ? null : rpc.getCode(), rpc == null ? null : rpc.getMessage());
            throw new BizException("Query agent info failed");
        }
        List<AgentInfoDto> data = rpc.getData();
        if (CollectionUtils.isEmpty(data)) {
            return List.of();
        }
        List<AgentInfoDto> result = new ArrayList<>();
        int crossTenant = 0;
        for (AgentInfoDto agent : data) {
            if (sameTenant(tenantId, agent.getTenantId())) {
                result.add(agent);
            } else {
                crossTenant++;
            }
        }
        if (crossTenant > 0) {
            log.warn("Internal agent query cross-tenant rows skipped, tenantId={}, skipped={}", tenantId, crossTenant);
        }
        return result;
    }

    /**
     * 目标行是否属于调用方声明的租户；不匹配一律视为「查不到」（fail-closed）
     */
    private static boolean sameTenant(Long expectedTenantId, Long actualTenantId) {
        return expectedTenantId != null && expectedTenantId.equals(actualTenantId);
    }

    /**
     * UserDto → UserDetailDto：password/resetPass/langMap 不在目标 DTO 上，天然不下发；
     * status/role 源为枚举、目标为 String，BeanUtils 因类型不匹配会跳过，这里显式转枚举名。
     */
    private static UserDetailDto toDetailDto(UserDto user) {
        if (user == null) {
            return null;
        }
        UserDetailDto dto = new UserDetailDto();
        BeanUtils.copyProperties(user, dto);
        dto.setStatus(user.getStatus() == null ? null : user.getStatus().name());
        dto.setRole(user.getRole() == null ? null : user.getRole().name());
        return dto;
    }

    /**
     * 代指定用户操作时，校验 userId 属于调用方声明的租户，不存在或跨租户一律拒绝。
     * <p>
     * 用户域的底层查询刻意豁免租户过滤（登录链路需要按 userId 跨租户查人），所以这里必须自己收口，
     * 不能依赖 SQL。注意本方法只证明「这个人是这个租户的」，不证明「调用方有权代表他」——后者是调用方
     * 业务侧的职责，内部 API 无从判断。
     */
    private UserDto requireTenantUser(Long tenantId, Long userId, String fieldName) {
        return requireTenantUser(tenantId, userId, fieldName, false);
    }

    /**
     * {@code optional=true} 时 userId 为空返回 null（用于 billUserId 这类可选入参）
     */
    private UserDto requireTenantUser(Long tenantId, Long userId, String fieldName, boolean optional) {
        if (userId == null || userId <= 0) {
            if (optional) {
                return null;
            }
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, fieldName);
        }
        UserDto user = userApplicationService.queryById(userId);
        if (user == null || !sameTenant(tenantId, user.getTenantId())) {
            log.warn("Internal operation user rejected, tenantId={}, {}={}, hitTenantId={}", tenantId, fieldName,
                    userId, user == null ? null : user.getTenantId());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemUserNotFound);
        }
        return user;
    }

    /**
     * 以目标用户身份执行 action：会话创建链路的权限校验、会话归属、计费主体都从
     * {@link RequestContext} 读当前用户，内部调用没有登录态，不「扮演」必然失败
     * （同 IM 渠道代用户发起会话的做法）。结束后恢复原值，不把身份留在同一请求的后续逻辑里。
     */
    private <R> R runAsUser(UserDto user, Supplier<R> action) {
        RequestContext<Object> context = RequestContext.get();
        Object previousUser = context.getUser();
        Long previousUserId = context.getUserId();
        try {
            context.setUser(user);
            context.setUserId(user.getId());
            return action.get();
        } finally {
            context.setUser(previousUser);
            context.setUserId(previousUserId);
        }
    }

    /**
     * SpaceUserDto → 内部 API 契约；role 源为枚举、目标为 String，显式转枚举名
     */
    private static InternalSpaceUserDto toSpaceUserDto(SpaceUserDto member) {
        InternalSpaceUserDto dto = new InternalSpaceUserDto();
        BeanUtils.copyProperties(member, dto);
        dto.setRole(member.getRole() == null ? null : member.getRole().name());
        return dto;
    }

    /**
     * 单成员版：{@code querySpaceUser} 只回空间成员行本身（不含 userName/nickName/avatar），
     * 而成员列表接口的契约里这几个字段是有值的，故这里用已查到的用户补齐，避免同一契约两种口径。
     */
    private static InternalSpaceUserDto toSpaceUserDto(SpaceUserDto member, UserDto user) {
        InternalSpaceUserDto dto = toSpaceUserDto(member);
        if (user != null) {
            dto.setUserName(user.getUserName());
            dto.setNickName(user.getNickName());
            dto.setAvatar(user.getAvatar());
        }
        return dto;
    }

    /**
     * SpaceDto → 内部 API 契约；id 暴露为 spaceId，type 枚举转名
     */
    private static InternalSpaceDto toSpaceDto(SpaceDto space) {
        if (space == null) {
            return null;
        }
        InternalSpaceDto dto = new InternalSpaceDto();
        BeanUtils.copyProperties(space, dto);
        dto.setSpaceId(space.getId());
        dto.setType(space.getType() == null ? null : space.getType().name());
        return dto;
    }

    /**
     * 校验空间存在且属于调用方声明的租户，返回空间信息。
     * <p>
     * 与 {@link #requireTenantUser} 同理：空间查询虽会被 MP 租户拦截器过滤，但那样「空间不存在」与
     * 「空间属于别的租户」都表现成同一种「查不到」，且底层过滤行为不是本控制器的契约保证，
     * 所以这里显式收口——跨租户一律当作「空间不存在」（3026）。
     */
    private SpaceDto requireTenantSpace(Long tenantId, Long spaceId, String fieldName) {
        if (spaceId == null || spaceId <= 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, fieldName);
        }
        SpaceDto space = spaceApplicationService.queryById(spaceId);
        if (space == null || !sameTenant(tenantId, space.getTenantId())) {
            log.warn("Internal space operation rejected, tenantId={}, {}={}, hitTenantId={}", tenantId, fieldName,
                    spaceId, space == null ? null : space.getTenantId());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSpaceNotFound);
        }
        return space;
    }

    /**
     * 解析成员角色：不传按 User 处理，只接受 Admin / User，大小写不敏感，非法值显式报错而不静默降级。
     * <p>
     * 不接受 Owner——一个空间只有一个 Owner（即 {@code space.creator_id}），把成员直接写成 Owner 会
     * 造出第二个所有者；转让所有权属于页面端转让接口的职责（与页面端 addSpaceUser 拒绝 Owner 一致）。
     */
    private static SpaceUser.Role parseMemberRole(String role) {
        if (StringUtils.isBlank(role)) {
            return SpaceUser.Role.User;
        }
        for (SpaceUser.Role candidate : SpaceUser.Role.values()) {
            if (!candidate.name().equalsIgnoreCase(role.trim())) {
                continue;
            }
            if (candidate == SpaceUser.Role.Owner) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemCannotAddOwner);
            }
            return candidate;
        }
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                "role 只能传 Admin 或 User", "role must be Admin or User.");
    }

    /**
     * 解析空间类型：不传按 Group 处理，当前<b>只签发 Group 与 DM</b>，传其它类型一律拒绝（3039）。
     * <p>
     * 白名单而不是黑名单：将来若要放开 Team，需要连同「团队空间数量配额」一起考虑，故故意不默许。
     */
    private static Space.Type parseSpaceType(String type) {
        if (StringUtils.isBlank(type)) {
            return Space.Type.Group;
        }
        for (Space.Type candidate : Space.Type.values()) {
            if (!candidate.name().equalsIgnoreCase(type.trim())) {
                continue;
            }
            if (!isInternalManagedType(candidate)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM,
                        BizExceptionCodeEnum.systemSpaceTypeCreateForbidden);
            }
            return candidate;
        }
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                "type 只能传 Group 或 DM", "type must be Group or DM.");
    }

    /**
     * 内部接口可管控的空间类型白名单：Group（群组）与 DM（私聊）。
     * <p>
     * 两者在创建 / 删除 / 成员增删上的管控完全一致，唯一差别是 DM 创建时可选传 {@code peerUserId}
     * 把「对方」一并写成成员，故白名单只此一处，避免三个入口各写一份判断而漂移。
     * <p>
     * 明确排除：Personal（删了会毁掉用户的个人空间）、Team / Class（页面端管理的既有空间，
     * 创建还要受团队空间配额约束，权限仍留在页面端）。
     */
    private static boolean isInternalManagedType(Space.Type type) {
        return type == Space.Type.Group || type == Space.Type.DM;
    }

    /**
     * 解析并校验 {@code peerUserId}（私聊对方），返回可信的用户ID，无值时返回 null。
     * <p>
     * 三条刻意的边界：
     * <ol>
     *   <li>只有 DM 接受该参数。非 DM 传了就报错，而不是静默忽略——静默忽略会让调用方以为
     *       对方已经进群，直到发现成员列表里没有人。</li>
     *   <li>对方必须与创建者同租户（走 {@link #requireTenantUser} 的显式归属校验），
     *       否则跨租户用户ID会被写进本租户空间的成员表。</li>
     *   <li>不能与创建者同一人：创建者已由底层自动写成 Owner 成员，再写一次既无意义，
     *       也会把私聊空间变成「和自己聊」。</li>
     * </ol>
     */
    private Long resolvePeerUserId(Long tenantId, Long peerUserId, Space.Type type, Long ownerUserId) {
        if (peerUserId == null) {
            return null;
        }
        if (type != Space.Type.DM) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "peerUserId 仅对 DM 类型有效", "peerUserId is only supported when type is DM.");
        }
        UserDto peer = requireTenantUser(tenantId, peerUserId, "peerUserId");
        if (peer.getId().equals(ownerUserId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "peerUserId 不能与 ownerUserId 相同", "peerUserId must differ from ownerUserId.");
        }
        return peer.getId();
    }

    /**
     * 把私聊对方写成空间成员（role=User），失败则补偿删除刚建的空间。
     * <p>
     * 为什么是 role=User 而不是 Owner：一个空间只有一个 Owner（{@code space.creator_id}），
     * 造出第二个所有者会破坏删除 / 转让的既有假设；且与成员增删接口的默认角色一致。
     * <p>
     * 为什么要补偿：建空间与写成员是两次独立调用，没有跨服务事务；成员写失败却把空空间留在库里，
     * 会得到一个「创建者跟自己的私聊」这种残留数据。补偿本身失败只记日志（空间 ID 已在日志里，
     * 可人工清理），不再往上抛——否则会把真实根因（成员写失败的原因）盖掉。
     */
    private void attachPeerMember(Long spaceId, Long peerUserId) {
        SpaceUserDto peer = new SpaceUserDto();
        peer.setSpaceId(spaceId);
        peer.setUserId(peerUserId);
        peer.setRole(SpaceUser.Role.User);
        try {
            spaceApplicationService.addSpaceUser(peer);
        } catch (Exception e) {
            log.error("Internal create DM space failed to attach peer member, rolling back space. spaceId={}, peerUserId={}",
                    spaceId, peerUserId, e);
            try {
                spaceApplicationService.delete(spaceId);
            } catch (Exception rollbackError) {
                log.error("Internal create DM space rollback failed, spaceId={} needs manual cleanup",
                        spaceId, rollbackError);
            }
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "写入私聊对方成员失败，空间已回滚", "Failed to attach the peer member; the space has been rolled back.");
        }
    }

    /**
     * ConversationDto → 内部 API 契约；id 暴露为 conversationId，type 枚举转名
     */
    private static InternalConversationDto toConversationDto(ConversationDto conversation) {
        if (conversation == null) {
            return null;
        }
        InternalConversationDto dto = new InternalConversationDto();
        BeanUtils.copyProperties(conversation, dto);
        dto.setConversationId(conversation.getId());
        dto.setType(conversation.getType() == null ? null : conversation.getType().name());
        return dto;
    }
}
