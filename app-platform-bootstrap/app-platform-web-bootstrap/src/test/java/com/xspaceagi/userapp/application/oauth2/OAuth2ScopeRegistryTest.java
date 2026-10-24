package com.xspaceagi.userapp.application.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * {@link OAuth2ScopeRegistry} 的单元测试——纯静态，不起 Spring 容器、不连库。
 * <p>
 * 这个类是「access_token 能调哪些接口」的唯一真相源，而它的失效方式<b>全是静默的</b>：
 * 漏登记路径 ⇒ 端点返回 scope 不足（看起来像客户端没申请对）；
 * 多登记或模式写宽 ⇒ 越权（没有任何报错）。
 * 所以这里的断言刻意写成<b>全集遍历</b>而不是挑几个样本：
 * 新增 scope 时若忘记登记路径、或路径写得能被别的 scope 覆盖，测试当场就红。
 */
class OAuth2ScopeRegistryTest {

    /** 反射取私有 SCOPE_PATTERNS（唯一真相源本身，不测副本） */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> patterns() {
        return (Map<String, List<String>>) ReflectionTestUtils.getField(OAuth2ScopeRegistry.class, "SCOPE_PATTERNS");
    }

    // ================================================================== 注册表自洽性

    @Test
    @DisplayName("SCOPE_METAS 与 SCOPE_PATTERNS 的 scope 集合完全一致（漏登记 / 多登记都会红）")
    void declaredScopesAndPatternsAreInSync() {
        Set<String> declared = new HashSet<>(OAuth2ScopeRegistry.supportedScopes());
        Set<String> withPatterns = new HashSet<>(patterns().keySet());
        assertEquals(declared, withPatterns,
                "scope 声明与路径登记不一致：只在 metas 里=" + diff(declared, withPatterns)
                        + "，只在 patterns 里=" + diff(withPatterns, declared));
    }

    @Test
    @DisplayName("scope 全集无重复，且保留首版的 profile / user:search 不动")
    void supportedScopesAreUniqueAndKeepLegacy() {
        List<String> scopes = OAuth2ScopeRegistry.supportedScopes();
        assertEquals(new HashSet<>(scopes).size(), scopes.size(), "存在重复声明的 scope");
        assertTrue(scopes.contains(OAuth2ScopeRegistry.SCOPE_PROFILE), "首版 scope profile 被移除，会影响已接入应用");
        assertTrue(scopes.contains(OAuth2ScopeRegistry.SCOPE_USER_SEARCH),
                "首版 scope user:search 被移除，会影响已接入应用");
    }

    @Test
    @DisplayName("所有登记路径都在 /api/oauth2/ 下（/api/v1/ 会被 ApiKeyInterceptor 吞掉）")
    void allPatternsStayInsideFacadeNamespace() {
        patterns().forEach((scope, list) -> list.forEach(pattern -> {
            assertTrue(pattern.startsWith("/api/oauth2/"),
                    "scope " + scope + " 登记了门面之外的路径 " + pattern
                            + "：/api/v1/ 前缀由 ApiKeyInterceptor 独占鉴权，oa- 令牌打不进去");
            assertFalse(pattern.contains("?"),
                    "scope " + scope + " 的路径 " + pattern + " 带查询串，Ant 匹配前会先剥掉查询串，永远匹配不上");
        }));
    }

    @Test
    @DisplayName("协议端点 token / revoke 不受 scope 管控（它们在 auth.excludePath 里，匿名可调）")
    void protocolEndpointsAreNotScopeGated() {
        Set<String> all = new HashSet<>();
        patterns().values().forEach(all::addAll);
        assertFalse(all.contains("/api/oauth2/token"), "/token 被 scope 管控会导致换令牌死循环");
        assertFalse(all.contains("/api/oauth2/revoke"), "/revoke 被 scope 管控会导致令牌失效时无法吊销");
    }

    // ================================================================== 路径 ↔ scope 的精确对应

    @Test
    @DisplayName("每条登记路径都被自己的 scope 放行")
    void everyPatternIsAllowedByItsOwnScope() {
        patterns().forEach((scope, list) -> list.forEach(pattern -> assertTrue(
                OAuth2ScopeRegistry.allows(List.of(scope), samplePath(pattern)),
                "scope " + scope + " 没有放行自己登记的路径 " + pattern)));
    }

    @Test
    @DisplayName("每条路径都不被其它 scope 放行（读写分离的真正判据）")
    void noPatternLeaksIntoAnotherScope() {
        patterns().forEach((scope, list) -> list.forEach(pattern -> patterns().forEach((other, ignored) -> {
            if (other.equals(scope)) {
                return;
            }
            assertFalse(OAuth2ScopeRegistry.allows(List.of(other), samplePath(pattern)),
                    "路径 " + pattern + "（属 " + scope + "）被 " + other + " 放行了——scope 之间出现越权");
        })));
    }

    @Test
    @DisplayName("chat 读写分离：detail 只归 chat:read，delete 只归 chat:write")
    void chatReadAndWriteAreSeparable() {
        String detail = "/api/oauth2/chat/conversation/detail";
        String delete = "/api/oauth2/chat/conversation/delete";
        assertTrue(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_CHAT_READ), detail));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_CHAT_WRITE), detail),
                "只写权限的令牌能读会话详情");
        assertTrue(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_CHAT_WRITE), delete));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_CHAT_READ), delete),
                "只读权限的令牌能删会话");
    }

    @Test
    @DisplayName("model:key 的 ** 模式覆盖四个子路径，但不覆盖 model/list")
    void modelKeyWildcardCoversApiKeyEndpointsOnly() {
        List<String> key = List.of(OAuth2ScopeRegistry.SCOPE_MODEL_KEY);
        for (String path : List.of("/api/oauth2/model/api-key/create", "/api/oauth2/model/api-key/list",
                "/api/oauth2/model/api-key/update", "/api/oauth2/model/api-key/revoke")) {
            assertTrue(OAuth2ScopeRegistry.allows(key, path), path + " 未被 model:key 覆盖");
            assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_MODEL_READ), path),
                    path + " 被 model:read 放行了——查模型权限不应能签发凭据");
        }
        assertTrue(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_MODEL_READ), "/api/oauth2/model/list"));
        assertFalse(OAuth2ScopeRegistry.allows(key, "/api/oauth2/model/list"),
                "model:key 覆盖了 model/list：签发权限顺带拿到查询权限，scope 粒度失效");
    }

    @Test
    @DisplayName("空 scope / 空路径 / 未登记路径一律拒绝（fail-closed）")
    void unknownInputIsRejected() {
        assertFalse(OAuth2ScopeRegistry.allows(null, "/api/oauth2/userinfo"));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(), "/api/oauth2/userinfo"));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_PROFILE), null));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_PROFILE), "  "));
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_PROFILE), "/api/oauth2/userinfo2"),
                "前缀相似的路径被放行，说明模式写宽了");
        assertFalse(OAuth2ScopeRegistry.allows(List.of("not-a-scope"), "/api/oauth2/userinfo"),
                "未声明的 scope 不应放行任何路径");
    }

    @Test
    @DisplayName("匹配前剥掉查询串")
    void queryStringIsStrippedBeforeMatching() {
        assertTrue(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_PROFILE),
                "/api/oauth2/userinfo?nocache=1&t=2"));
        assertTrue(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_CHAT_READ),
                "/api/oauth2/chat/message/list?conversationId=1"));
    }

    @Test
    @DisplayName("user:phone 是字段级权限：单独持有不放行 /userinfo，需与 profile 一起授予")
    void phoneScopeIsFieldLevelOnly() {
        assertFalse(OAuth2ScopeRegistry.allows(List.of(OAuth2ScopeRegistry.SCOPE_USER_PHONE),
                        "/api/oauth2/userinfo"),
                "只持 user:phone 就能调 /userinfo，等于绕过了 profile");
        assertTrue(OAuth2ScopeRegistry.allows(
                List.of(OAuth2ScopeRegistry.SCOPE_PROFILE, OAuth2ScopeRegistry.SCOPE_USER_PHONE),
                "/api/oauth2/userinfo"));
        assertEquals(List.of(OAuth2ScopeRegistry.SCOPE_USER_PHONE),
                OAuth2ScopeRegistry.parseRequested("user:phone"),
                "user:phone 未登记进 SCOPE_PATTERNS，申请时会被静默丢弃");
    }

    // ================================================================== scope 串解析

    @Test
    @DisplayName("parse：逗号与空白都作分隔符、去重、丢弃未知项，全空回落默认")
    void parseAcceptsBothSeparatorsAndFallsBackToDefault() {
        assertEquals(OAuth2ScopeRegistry.DEFAULT_SCOPES, OAuth2ScopeRegistry.parse(null));
        assertEquals(OAuth2ScopeRegistry.DEFAULT_SCOPES, OAuth2ScopeRegistry.parse("   "));
        assertEquals(OAuth2ScopeRegistry.DEFAULT_SCOPES, OAuth2ScopeRegistry.parse("bogus,other"),
                "全部非法时应回落默认 scope，而不是给出零权限的哑客户端");
        assertEquals(List.of("profile"), OAuth2ScopeRegistry.parse("profile,bogus"));
        // RFC 6749 §3.3 用空格分隔，平台库存串用逗号，两种都要认
        assertEquals(List.of("profile", "im:read"), OAuth2ScopeRegistry.parse("profile im:read"));
        assertEquals(List.of("profile", "im:read"), OAuth2ScopeRegistry.parse("profile, im:read ,"));
        assertEquals(List.of("profile"), OAuth2ScopeRegistry.parse("profile profile"), "未去重");
    }

    @Test
    @DisplayName("parseRequested 不回落默认值（否则申请不存在的 scope 反而拿到权限）")
    void parseRequestedNeverFallsBack() {
        assertEquals(List.of(), OAuth2ScopeRegistry.parseRequested(null));
        assertEquals(List.of(), OAuth2ScopeRegistry.parseRequested("  "));
        assertEquals(List.of(), OAuth2ScopeRegistry.parseRequested("bogus"),
                "parseRequested 回落了默认 scope：调用方申请一个不存在的 scope 就能拿到 profile");
        assertEquals(List.of("im:push"), OAuth2ScopeRegistry.parseRequested("im:push bogus"));
    }

    @Test
    @DisplayName("normalizeToStored：去重 + 丢弃未知项 + 逗号拼接；空列表得空串；全非法抛 oauth2InvalidScope")
    void normalizeToStoredIsTheOnlyWritePath() {
        assertEquals("", OAuth2ScopeRegistry.normalizeToStored(null));
        assertEquals("", OAuth2ScopeRegistry.normalizeToStored(List.of()));
        assertEquals("profile,im:read", OAuth2ScopeRegistry.normalizeToStored(List.of("profile", "im:read")));
        assertEquals("profile", OAuth2ScopeRegistry.normalizeToStored(List.of("profile", "profile", "bogus")));
        BizException e = assertThrows(BizException.class,
                () -> OAuth2ScopeRegistry.normalizeToStored(List.of("bogus", "also-bogus")),
                "全非法的 scope 应被拒绝，而不是静默存成空串（= 回落默认权限，申请人以为清空了其实拿到了 profile）");
        // getMsgName() 回的是业务枚举常量名（用于 i18n），不是 ErrorCodeEnum 的对外 code
        assertEquals(BizExceptionCodeEnum.oauth2InvalidScope.name(), e.getMsgName(),
                "应回 oauth2InvalidScope，调用方据此提示「scope 非法」");
    }

    @Test
    @DisplayName("toList 不过滤白名单（存量库里可能有已退役的 scope，过滤会让设置页展示失真）")
    void toListKeepsUnknownValues() {
        assertEquals(List.of(), OAuth2ScopeRegistry.toList(null));
        assertEquals(List.of(), OAuth2ScopeRegistry.toList("  "));
        assertEquals(List.of("profile", "retired:scope"), OAuth2ScopeRegistry.toList(" profile , retired:scope "));
    }

    // ================================================================== 默认授予范围

    @Test
    @DisplayName("默认 scope 不含敏感项、也不含「仅授权码」项")
    void defaultScopesAreMinimal() {
        for (String scope : OAuth2ScopeRegistry.DEFAULT_SCOPES) {
            OAuth2ScopeRegistry.supportedScopeMetas().stream()
                    .filter(meta -> meta.scope().equals(scope))
                    .findFirst()
                    .ifPresent(meta -> {
                        assertFalse(meta.sensitive(), "敏感 scope " + scope + " 进了默认授予范围");
                        assertFalse(meta.authorizationCodeOnly(),
                                "仅授权码 scope " + scope + " 进了默认授予范围");
                    });
        }
        assertEquals(List.of(OAuth2ScopeRegistry.SCOPE_PROFILE), OAuth2ScopeRegistry.DEFAULT_SCOPES);
    }

    // ================================================================== M2M 限制

    @Test
    @DisplayName("im:* 与 model:* 仅限授权码模式，其余 scope M2M 可拿")
    void authorizationCodeOnlyCoversImAndModel() {
        for (String scope : List.of(OAuth2ScopeRegistry.SCOPE_IM_READ, OAuth2ScopeRegistry.SCOPE_IM_PUSH,
                OAuth2ScopeRegistry.SCOPE_MODEL_READ, OAuth2ScopeRegistry.SCOPE_MODEL_KEY)) {
            assertTrue(OAuth2ScopeRegistry.isAuthorizationCodeOnly(scope), scope + " 应仅限授权码模式");
        }
        for (String scope : List.of(OAuth2ScopeRegistry.SCOPE_PROFILE, OAuth2ScopeRegistry.SCOPE_USER_SEARCH,
                OAuth2ScopeRegistry.SCOPE_USER_SPACE, OAuth2ScopeRegistry.SCOPE_FILE_WRITE,
                OAuth2ScopeRegistry.SCOPE_FILE_READ, OAuth2ScopeRegistry.SCOPE_CHAT_READ,
                OAuth2ScopeRegistry.SCOPE_CHAT_WRITE, OAuth2ScopeRegistry.SCOPE_TOOL_EXEC)) {
            assertFalse(OAuth2ScopeRegistry.isAuthorizationCodeOnly(scope), scope + " 不应限制 M2M，会打死既有接入方");
        }
        assertFalse(OAuth2ScopeRegistry.isAuthorizationCodeOnly("not-a-scope"),
                "未知 scope 应返回 false，交给白名单判定去拒，而不是在这里冒充 M2M 限制");
        assertFalse(OAuth2ScopeRegistry.isAuthorizationCodeOnly(null));
    }

    @Test
    @DisplayName("findAuthorizationCodeOnly 只挑出受限项且保序，供 token 端点回 invalid_scope")
    void findAuthorizationCodeOnlyPicksRestrictedOnes() {
        assertEquals(List.of(), OAuth2ScopeRegistry.findAuthorizationCodeOnly(null));
        assertEquals(List.of(), OAuth2ScopeRegistry.findAuthorizationCodeOnly(List.of()));
        assertEquals(List.of(),
                OAuth2ScopeRegistry.findAuthorizationCodeOnly(List.of("profile", "chat:read", "tool:exec")),
                "全是 M2M 可用 scope 时不应有任何受限项");
        assertEquals(List.of("im:push", "model:key"), OAuth2ScopeRegistry
                .findAuthorizationCodeOnly(List.of("profile", "im:push", "chat:write", "model:key")));
    }

    @Test
    @DisplayName("每个 scope 的元数据都有非空说明（设置页要展示给申请人看）")
    void everyScopeHasDescription() {
        OAuth2ScopeRegistry.supportedScopeMetas().forEach(meta -> {
            assertEquals(meta.scope(), meta.scope().trim(), "scope 值带空白会导致存库后匹配不上");
            assertTrue(meta.description() != null && !meta.description().isBlank(),
                    "scope " + meta.scope() + " 缺少说明文案");
        });
    }

    // ================================================================== 辅助

    /** 把 Ant 模式转成一个能被它匹配的具体路径（{@code **} 段填一个占位） */
    private static String samplePath(String pattern) {
        return pattern.replace("/**", "/probe-1");
    }

    private static List<String> diff(Set<String> left, Set<String> right) {
        List<String> only = new ArrayList<>(left);
        only.removeAll(right);
        return only;
    }
}
