package com.xspaceagi.userapp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.userapp.application.oauth2.ThirdAppOAuthSettingResult;
import com.xspaceagi.userapp.application.service.IOAuth2ScopeApplyApplicationService;
import com.xspaceagi.userapp.application.service.impl.ThirdAppOAuth2ApplicationServiceImpl;
import com.xspaceagi.userapp.domain.model.UserProjectModel;
import com.xspaceagi.userapp.domain.service.IUserProjectDomainService;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry.Source;

/**
 * user_project「主回调地址 + 补充回调地址（带归属）」的单元测试——纯 mock，不起 Spring 容器、不连库。
 * <p>
 * 钉三件事：
 * <ol>
 *   <li><b>匹配</b>：生效集合 = {redirect_uri} ∪ set(各条目 uri)。写松（顺手做尾斜杠/大小写归一化）⇒
 *       未登记的地址也能拿到授权码，开放重定向；写严（只认补充列表、漏掉主地址）⇒ 存量应用升级后立刻
 *       全线授权失败，而报错只有 {@code invalid_request}，接入方看不出是自己配错了还是平台坏了。
 *       故「对的必须过、错的必须不过」两边都钉。</li>
 *   <li><b>归属</b>：补充列表是网页人工配置与沙箱里的 AI 共写的一列。整表替换会让任何一方把对方的条目
 *       一并抹掉，所以改成显式增删 + 服务端按 {@code source} 强制删除权限（AI 只能删自己加的）。
 *       这条规则防不住调用方绕过前端直接发请求，因此必须在 application 层生效、而不是靠前端不显示按钮。</li>
 *   <li><b>不影响原来的</b>：不带增删参数的请求，落库字段必须与升级前逐字一致（{@code redirect_uris}
 *       保持 null，仓储按非空字段更新 ⇒ 该列不被触碰）；主地址与补充列表都空的存量行，授权仍报
 *       {@code oauth2RedirectUriRequired}。</li>
 * </ol>
 */
class ThirdAppOAuth2RedirectUrisTest {

    private static final Long PROJECT_ID = 9001L;
    private static final Long OPERATOR_ID = 10086L;

    private static final String PRIMARY = "https://app.example.com/oauth/callback";
    private static final String EXTRA_A = "https://staging.example.com/oauth/callback";
    private static final String EXTRA_B = "https://dev.example.com/oauth/callback";

    private ThirdAppOAuth2ApplicationServiceImpl service;
    private IUserProjectDomainService userProjectDomainService;
    private IOAuth2ScopeApplyApplicationService scopeApplyService;

    @BeforeEach
    void setUp() {
        service = new ThirdAppOAuth2ApplicationServiceImpl();
        userProjectDomainService = mock(IUserProjectDomainService.class);
        scopeApplyService = mock(IOAuth2ScopeApplyApplicationService.class);
        ReflectionTestUtils.setField(service, "userProjectDomainService", userProjectDomainService);
        ReflectionTestUtils.setField(service, "oauth2ScopeApplyApplicationService", scopeApplyService);
    }

    /** 库中一行三方应用配置；不传补充条目时该列为 null（存量行、未跑迁移的形态） */
    private static UserProjectModel project(String primary, RedirectUriEntry... supplementary) {
        UserProjectModel project = new UserProjectModel();
        project.setId(PROJECT_ID);
        project.setRedirectUri(primary);
        project.setRedirectUris(supplementary.length == 0 ? null : new ArrayList<>(List.of(supplementary)));
        return project;
    }

    private static RedirectUriEntry entry(String uri, Source source) {
        return RedirectUriEntry.builder().uri(uri).source(source).createdBy(7L).createdAt(new Date(0L)).build();
    }

    /** 网页人工配置写入的条目 */
    private static RedirectUriEntry manual(String uri) {
        return entry(uri, Source.Manual);
    }

    /** 沙箱里的 AI 写入的条目 */
    private static RedirectUriEntry sandbox(String uri) {
        return entry(uri, Source.Sandbox);
    }

    /** 归属不明的历史条目（source 为 null）——删除判定必须 fail-closed */
    private static RedirectUriEntry unattributed(String uri) {
        return RedirectUriEntry.builder().uri(uri).build();
    }

    /** 只取 uri，便于与字面量列表比对（条目还带来源/创建人/时间，逐个断言太吵） */
    private static List<String> uris(List<RedirectUriEntry> entries) {
        return entries == null ? null : entries.stream().map(RedirectUriEntry::getUri).toList();
    }

    /** 断言抛出的业务异常正是某个细分码（按枚举常量名比对，不受中英文文案影响） */
    private static void assertBizCode(String expectedMsgName, Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        assertEquals(expectedMsgName, e.getMsgName(), "业务细分码不符");
    }

    private ThirdAppOAuthSettingResult save(UserProjectModel project, String homepageUrl, String primary,
            List<String> add, List<String> remove, List<String> scopes, Source source) {
        return service.saveSetting(project, homepageUrl, primary, add, remove, scopes, OPERATOR_ID, source);
    }

    /** 取本次真正要落库的 update 模型；从未触达仓储则返回 null */
    private UserProjectModel capturedUpdate() {
        ArgumentCaptor<UserProjectModel> captor = ArgumentCaptor.forClass(UserProjectModel.class);
        verify(userProjectDomainService).update(captor.capture());
        return captor.getValue();
    }

    // ============================================================ 匹配：对的必须过

    @Test
    @DisplayName("主地址命中：沿用升级前行为，必须通过")
    void primaryUriStillMatches() {
        service.verifyRedirectUri(project(PRIMARY, manual(EXTRA_A)), PRIMARY);
    }

    @Test
    @DisplayName("补充地址命中：多回调的核心能力，必须通过")
    void supplementaryUriMatches() {
        service.verifyRedirectUri(project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B)), EXTRA_B);
    }

    @Test
    @DisplayName("补充列表为 null（存量行、未跑迁移）：主地址照常通过")
    void nullSupplementaryKeepsPrimaryWorking() {
        service.verifyRedirectUri(project(PRIMARY), PRIMARY);
    }

    @Test
    @DisplayName("主地址为空但补充列表有值：放行（沙箱里的 AI 只往补充列表写，这是合法状态）")
    void supplementaryOnlyProjectAuthorizes() {
        // 上一版刻意做成「主地址为空即拒」，但那样 AI 自己登记的回调地址永远用不上，授权码流程走不通
        service.verifyRedirectUri(project(null, sandbox(EXTRA_A)), EXTRA_A);
        service.verifyRedirectUri(project("   ", sandbox(EXTRA_A)), EXTRA_A);
        // 主地址为空时，拿它当入参不该命中：空串不是登记过的回调地址
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project(null, sandbox(EXTRA_A)), ""));
    }

    // ============================================================ 匹配：错的必须不过

    @Test
    @DisplayName("两者都不命中：拒绝，报 oauth2RedirectUriMismatch")
    void unregisteredUriIsRejected() {
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project(PRIMARY, manual(EXTRA_A)), "https://evil.example.com/cb"));
    }

    @Test
    @DisplayName("不做任何 URL 规范化：尾斜杠、大小写、默认端口、多出 query 一律拒绝")
    void noNormalizationIsApplied() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));
        // 尾斜杠
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project, PRIMARY + "/"));
        // host 大小写
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project, "https://APP.example.com/oauth/callback"));
        // 显式默认端口
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project, "https://app.example.com:443/oauth/callback"));
        // 多出 query
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project, PRIMARY + "?x=1"));
    }

    @Test
    @DisplayName("前缀不算命中：登记 /oauth/callback 不接受 /oauth/callback/evil")
    void prefixIsNotAMatch() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project, PRIMARY + "/evil"));
    }

    @Test
    @DisplayName("生效集合整体为空：仍拒绝授权，报 oauth2RedirectUriRequired（存量行行为不变）")
    void emptyEffectiveSetBlocksAuthorization() {
        // 主地址空 + 补充列表 null（存量 363 行的形态）
        assertBizCode("oauth2RedirectUriRequired", () -> service.verifyRedirectUri(project(null), PRIMARY));
        // 主地址空白 + 补充列表空
        UserProjectModel empty = project("   ");
        empty.setRedirectUris(new ArrayList<>());
        assertBizCode("oauth2RedirectUriRequired", () -> service.verifyRedirectUri(empty, EXTRA_A));
        // 补充列表里只有脏条目（uri 空白）：等价于没有补充地址，不能因为「列表非空」就放行
        assertBizCode("oauth2RedirectUriRequired",
                () -> service.verifyRedirectUri(project(null, manual("  ")), EXTRA_A));
        // 生效集合为空时先报「没配」，比「不匹配」更贴近真实原因
        assertBizCode("oauth2RedirectUriRequired", () -> service.verifyRedirectUri(project(null), null));
    }

    @Test
    @DisplayName("入参为 null：不命中主地址也不命中补充列表，按不匹配拒绝而非 NPE")
    void nullIncomingUriIsRejectedNotNpe() {
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project(PRIMARY, manual(EXTRA_A)), null));
        // 主地址为 null 的合法状态同样不能 NPE（此时以入参为接收方调 equals）
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project(null, sandbox(EXTRA_A)), null));
    }

    // ============================================================ 写入：不影响原来的

    @Test
    @DisplayName("旧请求（不带增删参数）：redirect_uris 保持 null，仓储不触碰该列")
    void legacyRequestLeavesSupplementaryUntouched() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));

        save(project, null, "https://new.example.com/cb", null, null, null, Source.Manual);

        UserProjectModel update = capturedUpdate();
        assertNull(update.getRedirectUris(), "未提交增删参数时不得写该列");
        assertEquals("https://new.example.com/cb", update.getRedirectUri());
        // 主地址仍可单独修改，补充列表原样留在库中
        assertEquals(List.of(EXTRA_A), uris(project.getRedirectUris()));
    }

    @Test
    @DisplayName("只提交 homepageUrl：两个回调地址列都不动")
    void homepageOnlyTouchesNeitherUriColumn() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));

        save(project, "https://home.example.com", null, null, null, null, Source.Manual);

        UserProjectModel update = capturedUpdate();
        assertEquals("https://home.example.com", update.getHomepageUrl());
        assertNull(update.getRedirectUri());
        assertNull(update.getRedirectUris());
    }

    @Test
    @DisplayName("空的 add 列表不再等于清空：幂等重写同内容，一条都不丢")
    void emptyAddListIsIdempotentAndDoesNotClear() {
        // ★与升级前语义相反（旧版空数组 = 清空整表）。改成显式增删后，清空只能点名删——
        //   否则沙箱里一次不带参数的保存就会把网页上人工配的回调地址全抹掉，用户登录当场断掉。
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B));

        ThirdAppOAuthSettingResult result = save(project, null, null, List.of(), null, null, Source.Sandbox);

        assertEquals(List.of(EXTRA_A, EXTRA_B), uris(capturedUpdate().getRedirectUris()));
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("点名删掉全部条目：落库空列表（不是 null，否则仓储会跳过该列、清不掉），主地址保留")
    void removeAllEntriesClearsSupplementaryOnly() {
        UserProjectModel project = project(PRIMARY, sandbox(EXTRA_A), sandbox(EXTRA_B));

        save(project, null, null, null, List.of(EXTRA_A, EXTRA_B), null, Source.Sandbox);

        UserProjectModel update = capturedUpdate();
        assertTrue(update.getRedirectUris().isEmpty());
        assertNull(update.getRedirectUri(), "删补充地址不得动主地址");
        assertEquals(PRIMARY, project.getRedirectUri());
    }

    // ============================================================ 写入：归一化与限额

    @Test
    @DisplayName("trim + 丢弃空串 + 去重 + 摘掉与主地址重复项")
    void supplementaryIsNormalized() {
        UserProjectModel project = project(PRIMARY);
        List<String> submitted = new ArrayList<>(List.of(
                "  " + EXTRA_A + "  ", EXTRA_A, "", "   ", PRIMARY, EXTRA_B));

        save(project, null, null, submitted, null, null, Source.Manual);

        assertEquals(List.of(EXTRA_A, EXTRA_B), uris(capturedUpdate().getRedirectUris()),
                "应只剩 trim 去重后的两条，且不含主地址");
    }

    @Test
    @DisplayName("同批改主地址：新增项里与新主地址重复的按新值摘掉")
    void duplicateAgainstNewlySubmittedPrimaryIsDropped() {
        UserProjectModel project = project(PRIMARY);

        save(project, null, EXTRA_A, List.of(EXTRA_A, EXTRA_B), null, null, Source.Manual);

        UserProjectModel update = capturedUpdate();
        assertEquals(EXTRA_A, update.getRedirectUri());
        assertEquals(List.of(EXTRA_B), uris(update.getRedirectUris()), "与新主地址重复的项应由主地址承载");
    }

    @Test
    @DisplayName("非法地址（非绝对 http(s) / 无 host / 带 fragment）：整批拒绝，不落库")
    void invalidSupplementaryUriIsRejected() {
        UserProjectModel project = project(PRIMARY);
        assertBizCode("oauth2UrlInvalid", () -> save(project, null, null,
                List.of("ftp://a.example.com/cb"), null, null, Source.Sandbox));
        assertBizCode("oauth2UrlInvalid", () -> save(project, null, null,
                List.of("/relative/cb"), null, null, Source.Sandbox));
        assertBizCode("oauth2UrlInvalid", () -> save(project, null, null,
                List.of(PRIMARY + "#frag"), null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());
    }

    @Test
    @DisplayName("超过 20 条：拒绝")
    void tooManySupplementaryUrisIsRejected() {
        UserProjectModel project = project(PRIMARY);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            tooMany.add("https://host" + i + ".example.com/cb");
        }

        assertBizCode("oauth2RedirectUriExceeded",
                () -> save(project, null, null, tooMany, null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());
    }

    @Test
    @DisplayName("恰好 20 条：通过（边界不被误伤）")
    void exactlyTwentySupplementaryUrisIsAccepted() {
        UserProjectModel project = project(PRIMARY);
        List<String> max = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            max.add("https://host" + i + ".example.com/cb");
        }

        save(project, null, null, max, null, null, Source.Sandbox);

        assertEquals(20, capturedUpdate().getRedirectUris().size());
    }

    @Test
    @DisplayName("单条超过 512 字符：拒绝（与主地址列宽对齐）")
    void overlongSupplementaryUriIsRejected() {
        UserProjectModel project = project(PRIMARY);
        String overlong = "https://app.example.com/cb?" + "q".repeat(512);
        assertTrue(overlong.length() > 512);

        assertBizCode("oauth2RedirectUriExceeded",
                () -> save(project, null, null, List.of(overlong), null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());
    }

    @Test
    @DisplayName("query 参与精确匹配：带 query 的地址可登记，且必须整串相等才命中")
    void queryIsPartOfTheMatch() {
        String withQuery = "https://app.example.com/cb?env=dev";
        UserProjectModel project = project(PRIMARY);

        save(project, null, null, List.of(withQuery), null, null, Source.Sandbox);
        assertEquals(List.of(withQuery), uris(capturedUpdate().getRedirectUris()));

        service.verifyRedirectUri(project(PRIMARY, sandbox(withQuery)), withQuery);
        assertBizCode("oauth2RedirectUriMismatch",
                () -> service.verifyRedirectUri(project(PRIMARY, sandbox(withQuery)),
                        "https://app.example.com/cb?env=prod"));
    }

    // ============================================================ 写入：归属与删除权限

    @Test
    @DisplayName("沙箱新增：条目打上 Sandbox 来源 + 操作人 + 时间（服务端执行删除权限的唯一判据）")
    void sandboxAddStampsProvenance() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));

        save(project, null, null, List.of(EXTRA_B), null, null, Source.Sandbox);

        List<RedirectUriEntry> entries = capturedUpdate().getRedirectUris();
        assertEquals(List.of(EXTRA_A, EXTRA_B), uris(entries));
        RedirectUriEntry added = entries.get(1);
        assertEquals(Source.Sandbox, added.getSource());
        assertEquals(OPERATOR_ID, added.getCreatedBy());
        assertNotNull(added.getCreatedAt());
        // 既有人工条目原样保留，归属不被改写
        assertEquals(Source.Manual, entries.get(0).getSource());
    }

    @Test
    @DisplayName("人工新增：条目打上 Manual 来源")
    void manualAddStampsManualSource() {
        UserProjectModel project = project(PRIMARY);

        save(project, null, null, List.of(EXTRA_A), null, null, Source.Manual);

        RedirectUriEntry added = capturedUpdate().getRedirectUris().get(0);
        assertEquals(Source.Manual, added.getSource());
        assertEquals(OPERATOR_ID, added.getCreatedBy());
    }

    @Test
    @DisplayName("来源传 null：按人工处理（人工是全权方，收窄它会凭空丢权限）")
    void nullSourceIsTreatedAsManual() {
        UserProjectModel project = project(PRIMARY, sandbox(EXTRA_A));

        ThirdAppOAuthSettingResult result = save(project, null, "https://new.example.com/cb",
                null, List.of(EXTRA_A), null, null);

        assertTrue(capturedUpdate().getRedirectUris().isEmpty(), "null 来源按人工处理，可删任意条目");
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("沙箱删不掉人工条目：跳过 + 回报，不抛错，其余改动照常生效")
    void sandboxCannotRemoveManualEntry() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B));

        ThirdAppOAuthSettingResult result = save(project, null, null, null, List.of(EXTRA_A), null, Source.Sandbox);

        // ★刻意「跳过 + 回报」而不是抛错：抛错会让调用方以为整次失败而原样重试（永远失败），
        //   也会把已经生效的那部分改动掩盖掉
        assertEquals(List.of(EXTRA_A, EXTRA_B), uris(capturedUpdate().getRedirectUris()), "人工条目必须还在");
        assertEquals(List.of(EXTRA_A), result.skippedRedirectUriRemovals());
    }

    @Test
    @DisplayName("沙箱能删自己加的条目：删掉且不计入 skipped")
    void sandboxCanRemoveOwnEntry() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B));

        ThirdAppOAuthSettingResult result = save(project, null, null, null, List.of(EXTRA_B), null, Source.Sandbox);

        assertEquals(List.of(EXTRA_A), uris(capturedUpdate().getRedirectUris()));
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("人工可删任意来源的条目：应用属主对自己的应用全权")
    void manualCanRemoveAnyEntry() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B));

        ThirdAppOAuthSettingResult result = save(project, null, null, null,
                List.of(EXTRA_A, EXTRA_B), null, Source.Manual);

        assertTrue(capturedUpdate().getRedirectUris().isEmpty());
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("归属不明（source=null）的历史条目：沙箱删不动，fail-closed")
    void sandboxCannotRemoveUnattributedEntry() {
        UserProjectModel project = project(PRIMARY, unattributed(EXTRA_A));

        ThirdAppOAuthSettingResult result = save(project, null, null, null, List.of(EXTRA_A), null, Source.Sandbox);

        // 宁可让 AI 删不动，也不能把归属不明的条目当成它自己的放出去
        assertEquals(List.of(EXTRA_A), uris(capturedUpdate().getRedirectUris()));
        assertEquals(List.of(EXTRA_A), result.skippedRedirectUriRemovals());
    }

    @Test
    @DisplayName("删一个不存在的地址：no-op，不计入 skipped（它已经不在列表里，没有需要回报的事）")
    void removeNonExistentUriIsNoOpNotSkipped() {
        UserProjectModel project = project(PRIMARY, sandbox(EXTRA_A));

        ThirdAppOAuthSettingResult result = save(project, null, null, null,
                List.of("https://never-registered.example.com/cb"), null, Source.Sandbox);

        assertEquals(List.of(EXTRA_A), uris(capturedUpdate().getRedirectUris()));
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("混合批次：删自己的 + 删人工的 + 加新的，一次落库、部分成功且逐项可核对")
    void mixedBatchIsPartiallyAppliedInOneWrite() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A), sandbox(EXTRA_B));
        String fresh = "https://preview.example.com/oauth/callback";

        ThirdAppOAuthSettingResult result = save(project, null, null, List.of(fresh),
                List.of(EXTRA_A, EXTRA_B), null, Source.Sandbox);

        // 只 update 一次：增删在同一份 list 上顺序完成，避免两次写互相覆盖
        assertEquals(List.of(EXTRA_A, fresh), uris(capturedUpdate().getRedirectUris()));
        assertEquals(List.of(EXTRA_A), result.skippedRedirectUriRemovals());
        assertEquals(Source.Sandbox, capturedUpdate().getRedirectUris().get(1).getSource());
        // 返回的 project 与落库内容一致，调用方拿到的就是最终状态
        assertEquals(List.of(EXTRA_A, fresh), uris(result.project().getRedirectUris()));
    }

    @Test
    @DisplayName("重复添加已存在的地址：幂等，不产生第二条，且不改先来者的归属")
    void reAddingExistingUriKeepsOriginalProvenance() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));

        ThirdAppOAuthSettingResult result = save(project, null, null, List.of(EXTRA_A), null, null, Source.Sandbox);

        List<RedirectUriEntry> entries = capturedUpdate().getRedirectUris();
        assertEquals(List.of(EXTRA_A), uris(entries));
        // 若允许改写归属，AI 重跑一次就能把人工条目「认领」成自己的、进而获得删除权
        assertEquals(Source.Manual, entries.get(0).getSource());
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
    }

    @Test
    @DisplayName("无跳过项时 skipped 是空列表而不是 null，调用方不必判空")
    void skippedRemovalsIsNeverNull() {
        UserProjectModel project = project(PRIMARY);

        ThirdAppOAuthSettingResult result = save(project, null, null, List.of(EXTRA_A), null, null, Source.Sandbox);

        assertNotNull(result.skippedRedirectUriRemovals());
        assertTrue(result.skippedRedirectUriRemovals().isEmpty());
        // 完全不带增删参数时同样返回空列表
        UserProjectModel untouched = project(PRIMARY);
        assertTrue(save(untouched, "https://home.example.com", null, null, null, null, Source.Sandbox)
                .skippedRedirectUriRemovals().isEmpty());
    }

    // ============================================================ 写入：主地址对沙箱一律冻结

    @Test
    @DisplayName("沙箱改非空主地址：拒绝，报 oauth2RedirectUriPrimaryFrozen，一行都不落库")
    void sandboxCannotOverwriteNonBlankPrimary() {
        UserProjectModel project = project(PRIMARY, manual(EXTRA_A));

        assertBizCode("oauth2RedirectUriPrimaryFrozen",
                () -> save(project, null, "https://hijack.example.com/cb", null, null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());
        // 同批的其他改动一并回滚语义：主地址被拒时不该只写走一半
        assertEquals(PRIMARY, project.getRedirectUri());
    }

    @Test
    @DisplayName("沙箱在主地址为空时也不能写：没有「放行一次」的例外，主地址只属网页端")
    void sandboxCannotSetPrimaryEvenWhenBlank() {
        // 空串与 null 都要挡住：前者是「显式提交了空」，后者是「没提交」，但非空提交一律拒
        assertBizCode("oauth2RedirectUriPrimaryFrozen",
                () -> save(project("   "), null, EXTRA_A, null, null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());

        // ★这条是本次收紧的核心：AI 若在主地址为空时占位，人工之后想配主地址反而会被 3353 挡在门外，
        //   而且 AI 的回调地址本来走补充列表就够用（生效集合取并集，主地址为空不影响授权）。
        // 对照：同样一串地址走补充列表通道则照常成功，说明收紧的只是主地址那一格
        UserProjectModel blank = project("   ");
        save(blank, null, null, List.of(EXTRA_A), null, null, Source.Sandbox);
        assertEquals(List.of(EXTRA_A), uris(capturedUpdate().getRedirectUris()));
        assertNull(capturedUpdate().getRedirectUri());
    }

    @Test
    @DisplayName("沙箱在同一批里既改主地址又加补充地址：整批拒绝，不许部分成功")
    void sandboxPrimaryRejectionIsAllOrNothing() {
        assertBizCode("oauth2RedirectUriPrimaryFrozen",
                () -> save(project(""), null, EXTRA_A, List.of(EXTRA_B), null, null, Source.Sandbox));
        verify(userProjectDomainService, never()).update(any());
    }

    @Test
    @DisplayName("人工改主地址不受限：属主随时能改自己的回调地址")
    void manualMayOverwritePrimary() {
        UserProjectModel project = project(PRIMARY);

        save(project, null, EXTRA_A, null, null, null, Source.Manual);

        assertEquals(EXTRA_A, capturedUpdate().getRedirectUri());
        assertEquals(EXTRA_A, project.getRedirectUri());
    }

    @Test
    @DisplayName("沙箱改 homepageUrl 不受限：主页地址不是安全边界，保持原有可写性")
    void sandboxMayUpdateHomepage() {
        UserProjectModel project = project(PRIMARY);

        save(project, "https://home.example.com", null, null, null, null, Source.Sandbox);

        assertEquals("https://home.example.com", capturedUpdate().getHomepageUrl());
    }

    // ============================================================ scope 通道未被牵连

    @Test
    @DisplayName("scopes 仍按原样提交审核，不因回调地址改动而丢失")
    void scopeSubmissionIsUnaffected() {
        UserProjectModel project = project(PRIMARY);

        save(project, null, null, List.of(EXTRA_A), null, List.of("profile"), Source.Sandbox);

        verify(scopeApplyService).submit(project, OPERATOR_ID, List.of("profile"));
    }
}
