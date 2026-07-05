package com.xspaceagi.agent.core.infra.proxy;

import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;

/**
 * 计算机资源反向代理
 * 支持 desktop、audio、ime 等路径的代理
 */
@Component
public class ComputerProxyServerContainer {

    public static final AttributeKey<Channel> NEXT_CHANNEL = AttributeKey.newInstance("computerNextChannel");
    public static AttributeKey<String> tokenKey = AttributeKey.newInstance("computerTokenKey");
    public static final AttributeKey<String> SHARE_KEY = AttributeKey.newInstance("computerShareKey");
    public static final AttributeKey<String> SK_COOKIE_KEY = AttributeKey.newInstance("computerSKCookieKey");
    public static final AttributeKey<java.util.Date> SHARE_EXPIRE = AttributeKey.newInstance("computerShareExpire");
    private static final Logger log = LoggerFactory.getLogger(ComputerProxyServerContainer.class);

    // 前端识别此关键字，不可轻易修改
    public static final String shareExpiredMessage = "Share expired";

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    @Value("${computer.proxy.host:0.0.0.0}")
    private String proxyHost;

    @Value("${computer.proxy.port:18085}")
    private int proxyPort;

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Resource
    private UserShareRpcService userShareRpcService;

    @Resource
    private AuthService authService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    // 连接超时（毫秒），超过该时间还未连上目标，就判定超时失败并返回 502，不再等待
    private int connectTimeoutMs = 5000;

    // 空闲读超时（秒），用于清理长时间无流量的连接，Idle 超时自动断开双端
    private int readTimeoutSeconds = 600;

    // 待转发挂起队列上限（按消息个数），溢出直接 503/关闭，防止 DoS 占满内存
    private int pendingMax = 200;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);

        // 预创建 HTTP 和 HTTPS 客户端 Bootstrap
        Bootstrap httpClientBootstrap = buildClientBootstrap(workerGroup, false, connectTimeoutMs, readTimeoutSeconds);
        Bootstrap httpsClientBootstrap = buildClientBootstrap(workerGroup, true, connectTimeoutMs, readTimeoutSeconds);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // 提高监听队列容量，缓解突发建连被内核直接拒绝
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 前端空闲超时，主动清理僵尸连接
                        pipeline.addLast("serverIdle", new IdleStateHandler(readTimeoutSeconds, 0, 0));
                        pipeline.addLast("httpServerCodec", new HttpServerCodec());
                        pipeline.addLast("computerProxyRequestHandler",
                                new ComputerProxyRequestHandler(sandboxServerConfigService, userShareRpcService, authService,
                                        tenantConfigApplicationService, iCustomPageRpcService, spacePermissionService, httpClientBootstrap, httpsClientBootstrap, pendingMax));
                    }
                });

        serverBootstrap.bind(proxyHost, proxyPort).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Computer proxy server started at {}:{}", proxyHost, proxyPort);
            } else {
                log.error("Failed to start Computer proxy server", future.cause());
            }
        });
    }

    public static Bootstrap buildClientBootstrap(NioEventLoopGroup group, boolean enableSsl, int connectTimeoutMs, int readTimeoutSeconds) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs);

        // 开启 TCP keepalive，内核周期性探测空闲连接是否存活，及时发现死连接并回收资源，适合长连接场景（如 WebSocket）
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        // 关闭 Nagle 算法，数据尽快发送而不凑包，降低交互延迟，适合小包高频的实时流量
        bootstrap.option(ChannelOption.TCP_NODELAY, true);

        bootstrap.group(group).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (enableSsl) {
                    try {
                        SslContext sslContext = SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build();
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                    } catch (SSLException e) {
                        throw new RuntimeException("Init ssl context failed", e);
                    }
                }
                // 目标侧空闲超时，及时关闭
                ch.pipeline().addLast("clientIdle", new IdleStateHandler(readTimeoutSeconds, 0, 0));
                ch.pipeline().addLast("httpClientCodec", new HttpClientCodec());
                ch.pipeline().addLast("computerProxyResponseHandler", new ComputerProxyResponseHandler());
            }
        });
        return bootstrap;
    }

    public static String buildOrigin(String scheme, String host, int port) {
        boolean isHttpDefault = "http".equalsIgnoreCase(scheme) && port == 80;
        boolean isHttpsDefault = "https".equalsIgnoreCase(scheme) && port == 443;
        if (isHttpDefault || isHttpsDefault) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    @PreDestroy
    public void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}

