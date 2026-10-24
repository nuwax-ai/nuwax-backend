package com.xspaceagi.connector.domain.service;

import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.exec.OAuthTokenProvider;

import java.util.List;

/**
 * OAuth2 连接服务（设计文档 §4.3：仅用于建立连接，不复用于平台登录）。
 * <p>
 * state 一次性原子消费防重放；PKCE verifier 与 client_secret 加密存储；
 * token 只进凭证保险库，任何返回值不含密钥。
 */
public interface ConnectorOAuthService extends OAuthTokenProvider {

    /**
     * 生成授权 URL：创建 state（SHA-256 落库）+ PKCE，TTL 15 分钟。
     */
    String buildAuthorizeUrl(String providerService, Long spaceId);

    /**
     * 处理授权回调：原子消费 state → code 换 token → 凭证入库 → 建连接。
     * 返回完成页渲染所需信息（不含任何密钥）。
     */
    CallbackOutcome handleCallback(String state, String code, String providerError);

    /**
     * 保存 OAuth App 配置。platform 轨 ownerId=0（管理侧），byo 轨 ownerId=用户id。
     * <p>
     * 首次配置要求 clientId/authUrl/tokenUrl/clientSecret 齐全；已存在时按<b>部分更新</b>处理，
     * 传空的字段（含 clientSecretPlain）一律沿用已存值，scopes 传 {@code null} 亦沿用、传空数组才清空。
     */
    void saveConfig(String providerService, String scopeType, Long ownerId,
                    String clientId, String clientSecretPlain, String authUrl, String tokenUrl,
                    List<String> scopes);

    /**
     * 查看配置（client_secret 只露有无，不露值）。
     */
    OauthConfigView getConfig(String providerService, String scopeType, Long ownerId);

    /**
     * 同 {@link OAuthTokenProvider#validAccessToken}，但由调用方指定「离到期多久就算该刷新」。
     * <p>
     * 执行引擎固定用 60 秒余量，那是给它自己「马上就发请求」准备的，刷新完立刻用掉。但
     * {@code get_auth_config} 是把令牌<b>发放出去</b>给客户端离线使用（例如注入 lark-cli 的环境
     * 变量），发出去之后就脱离了平台，60 秒余量等于递出去一张马上就要作废的票。它需要一个按自己
     * 口径复核余量的入口，而不是把引擎的余量调大——引擎每次调用都刷新会白白增加 IdP 往返。
     *
     * @param refreshSkewMs 剩余有效期低于该值即先刷新，而非直接取用
     */
    String validAccessToken(ConnectorConnection connection, long refreshSkewMs);

    // ---------- 设备码（扫码）流程 ----------

    /**
     * 发起扫码授权：调设备授权端点拿 device_code，创建 state 行并返回轮询句柄。
     * <p>
     * device_code 与 verification_uri_complete 都<b>不返回给调用方</b>，只加密存进 state 行；
     * 二维码由 {@code /device/qr} 端点按 state 在服务端渲染，授权 URL 因此不经客户端。
     * 返回的 userCode 仅供展示（用户在确认页可核对），单独持有它无法完成授权。
     */
    DeviceBeginView beginDeviceFlow(String providerService, Long spaceId);

    /**
     * 按 state 取出待扫码的授权 URL 明文，供 UI 层渲染二维码。
     * <p>
     * 这是本接口里唯一一处把授权 URL 交出模块服务层的方法，用途仅限出图；
     * 校验归属与有效期，state 不属于当前登录用户或已失效时抛异常。不消费 state。
     */
    String deviceVerificationUrl(String state);

    /**
     * 轮询一次设备码授权结果；拿到令牌则落凭证并建连。
     * <p>
     * 服务端不 sleep：把下一次轮询的间隔回给调用方自己等，避免长时间占用请求线程。
     * {@code clientInterval} 是调用方上一轮拿到的间隔，用于在收到 slow_down 时递增。
     */
    DevicePollView pollDeviceFlow(String state, Integer clientInterval);

    /**
     * 取出指定用户可用的应用凭证明文（含 client_secret），仅供模块内 CUSTOM 运行时
     * 发放凭证给客户端使用。
     * <p>
     * ⚠️ 这是对「凭证明文不出 connector 模块边界」的<b>有意放宽</b>：{@code get_auth_config}
     * 这类工具的职责就是把凭证交给沙箱里的客户端（如 lark-cli 的环境变量注入）。
     * 调用方不得把返回值写入日志或异常消息。
     */
    AppCredentialView resolveAppCredential(String providerService, Long userId);

    /**
     * 回收该用户在某 provider 名下<b>由扫码自注册产生</b>的 App 配置（{@code scope_type=byo} 那一行）。
     * <p>
     * 断开连接时必须调用，否则会留下一个致命残骸：{@link #beginDeviceFlow} 的分岔判据是
     * 「该用户解析到的配置行有没有 client_id」，而那行 byo 配置一旦留下，判据就<b>永远为假</b>。
     * 用户在 IdP 侧把那个应用删掉之后再回来扫码，平台会拿着已经失效的 client_id 去发设备授权请求，
     * 换来一句「应用不存在」，而自注册分支再也走不回去了——断连时清掉这行，用户重新扫码即可自愈。
     * <p>
     * <b>只回收自注册的产物</b>：判据是该 provider 是否声明了 {@code auth_config.appRegistration}，
     * 或是否命中已知 IdP 的自注册画像。{@code oauth_app_mode=byo} 下用户<b>手填</b>自己的 App 时，
     * 那行配置是用户的资产（重连还要用），不在回收之列。
     * <p>
     * <b>还有别的连接就不回收</b>：byo 行是<b>用户级</b>的，同一用户对同一 provider 可以建出多条连接，
     * 它们共用同一份 App 凭证。断连是断开其中一条，副作用不该打到剩下的那些身上——删了配置，它们就
     * 再也刷不出令牌。
     * <p>
     * provider 已不可查（被删或不可见）时静默跳过：判据不完整时宁可留着，也不误删用户手填的 App。
     * 本方法不抛异常、不阻断断连。
     *
     * @param tenantId 调用方（通常是被断开的那条连接）的租户，用于查该用户名下还剩几条连接
     */
    void releaseRegisteredApp(String providerService, Long userId, Long tenantId);

    /**
     * 扫码授权发起结果。
     *
     * @param mode      本次走的是哪条分支：{@code register}（在 IdP 上注册一个新应用，产出应用身份）
     *                  或 {@code authorize}（对已有应用做用户授权，产出用户身份）。
     *                  <b>由服务端定调而不是让调用方自己推断</b>：同一个 provider 会先后经历两种模式
     *                  （配置里配了自注册段，但第一次扫码写完 byo 配置行后此判据即转假），
     *                  调用方照配置猜必然把第二次也当成注册。界面文案、下一步引导都依赖这个字段。
     * @param state     轮询句柄（32B 随机值，SHA-256 落库）
     * @param userCode  用户在确认页看到的短码，仅供核对展示
     * @param expiresIn 本次授权窗口秒数（实测飞书为 600）
     * @param interval  建议轮询间隔秒数（实测飞书为 5）
     */
    record DeviceBeginView(String mode, String state, String userCode, int expiresIn, int interval) {

        /** {@link #mode} 取值：本次扫码是在 IdP 上注册新应用 */
        public static final String MODE_REGISTER = "register";

        /** {@link #mode} 取值：本次扫码是对已有应用做用户授权 */
        public static final String MODE_AUTHORIZE = "authorize";
    }

    /**
     * 扫码轮询结果。
     * <p>
     * 只有 {@code pending} 是非终态，其余五种都应停止轮询。
     *
     * @param status       {@code pending}（继续轮询）/ {@code authorized}（已建连）/
     *                     {@code denied}（用户拒绝）/ {@code expired}（授权窗口已过）/
     *                     {@code already_completed}（另一标签页已完成建连）/
     *                     {@code failed}（上游返回未知错误，确定性失败）
     * @param interval     下一次轮询应等待的秒数，仅 pending 时有值
     * @param connectionId 仅 authorized 时有值
     */
    record DevicePollView(String status, Integer interval, Long connectionId,
                          String displayName, String message) {
    }

    /**
     * 应用凭证明文。⚠️ 含 client_secret，不得写入日志。
     */
    record AppCredentialView(String appId, String clientSecret) {
    }

    /**
     * 回调结果（完成页 / JSON 版回调共用）。
     * <p>
     * connectionId 仅在 success=true 时有值：HTML 完成页用不到它，但走
     * {@code POST /connector/oauth/exchange} 的调用方（Agent/脚本）拿到它可以接着做
     * 校验、删除重建等后续操作，不必再查一次连接列表。
     */
    record CallbackOutcome(boolean success, String providerService, String displayName, String message,
                           Long connectionId) {
    }

    /**
     * 配置视图（脱敏）。
     */
    record OauthConfigView(String providerService, String scopeType, String clientId,
                           String authUrl, String tokenUrl, List<String> scopes, boolean hasClientSecret) {
    }
}
