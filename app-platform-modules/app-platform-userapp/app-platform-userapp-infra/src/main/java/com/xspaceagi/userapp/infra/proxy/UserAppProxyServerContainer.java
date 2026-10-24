package com.xspaceagi.userapp.infra.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
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
 * userapp 独立端口 HTTP 反向代理：
 * 前端访问（nginx 转发到本端口），代理内部查出创建者 user_id 后转发到项目绑定沙箱的 agent 服务。
 */
@Component
public class UserAppProxyServerContainer {

    public static AttributeKey<Channel> nextChannelAttributeKey = AttributeKey.newInstance("userappNextChannelAttributeKey");

    public static AttributeKey<String> tokenKey = AttributeKey.newInstance("userappTokenKey");

    /** 转发上下文（回传日志用）：挂在后端通道上，记录当前在途请求的 method/uri/后端/发起时间 */
    public static AttributeKey<UserAppProxyRelayContext> relayContextKey =
            AttributeKey.newInstance("userappRelayContextKey");

    private static Logger logger = LoggerFactory.getLogger(UserAppProxyServerContainer.class);

    private volatile boolean started = false;
    private NioEventLoopGroup serverBossGroup;
    private NioEventLoopGroup serverWorkerGroup;

    @Resource
    private UserAppProxyConfigService userAppProxyConfigService;

    @Value("${userapp.http-proxy.host:0.0.0.0}")
    private String httpProxyHost;

    @Value("${userapp.http-proxy.port:19001}")
    private String httpProxyPort;

    /** 空闲读超时（秒）：长时间无流量的前后端连接回收，Idle 超时自动断开双端 */
    private static final int READ_TIMEOUT_SECONDS = 600;

    /** 待转发挂起队列上限（按消息个数），溢出直接 503 关闭，防止连接建立期间内存被占满 */
    private static final int PENDING_MAX = 200;

    @PostConstruct
    public void init() {
        synchronized (UserAppProxyServerContainer.class) {
            if (!started) {
                initHttpProxyServer();
                started = true;
            }
        }
    }

    /**
     * http反向代理
     */
    private void initHttpProxyServer() {
        serverBossGroup = new NioEventLoopGroup(2);
        serverWorkerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        Bootstrap proxyClientBootstrap = new Bootstrap();
        SslContext context;
        try {
            context = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
        proxyClientBootstrap.channel(NioSocketChannel.class);
        // 沙箱主机不可达时避免连接永久挂起
        proxyClientBootstrap.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        // TCP keepalive 内核探活回收死连接；NODELAY 关闭 Nagle 凑包，vnc/终端小包低延迟
        proxyClientBootstrap.option(io.netty.channel.ChannelOption.SO_KEEPALIVE, true);
        proxyClientBootstrap.option(io.netty.channel.ChannelOption.TCP_NODELAY, true);
        proxyClientBootstrap.group(serverWorkerGroup).handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) {
                // 目标侧空闲超时，及时关闭
                ch.pipeline().addLast("clientIdle", new IdleStateHandler(READ_TIMEOUT_SECONDS, 0, 0));
                ch.pipeline().addLast("httpClientCodec", new HttpClientCodec());
                ch.pipeline().addLast(new UserAppProxyResponseHandler());
            }
        });

        Bootstrap proxySslClientBootstrap = new Bootstrap();
        proxySslClientBootstrap.channel(NioSocketChannel.class);
        proxySslClientBootstrap.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        proxySslClientBootstrap.option(io.netty.channel.ChannelOption.SO_KEEPALIVE, true);
        proxySslClientBootstrap.option(io.netty.channel.ChannelOption.TCP_NODELAY, true);
        proxySslClientBootstrap.group(serverWorkerGroup).handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(context.newHandler(ch.alloc()));
                ch.pipeline().addLast("clientIdle", new IdleStateHandler(READ_TIMEOUT_SECONDS, 0, 0));
                ch.pipeline().addLast("httpClientCodec", new HttpClientCodec());
                ch.pipeline().addLast(new UserAppProxyResponseHandler());
            }
        });

        ServerBootstrap httpServerBootstrap = new ServerBootstrap();
        // 提高监听队列容量，缓解突发建连被内核直接拒绝
        httpServerBootstrap.option(io.netty.channel.ChannelOption.SO_BACKLOG, 1024);
        httpServerBootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                logger.error("exceptionCaught", cause);
                super.exceptionCaught(ctx, cause);
            }

            @Override
            public void initChannel(SocketChannel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                // 前端空闲超时，主动清理僵尸连接（ws 隧道也生效，编解码器摘除不影响本 handler）
                pipeline.addLast("serverIdle", new IdleStateHandler(READ_TIMEOUT_SECONDS, 0, 0));
                pipeline.addLast("httpServerCodec", new HttpServerCodec());
                pipeline.addLast("userAppProxyRequestHandler", new UserAppProxyRequestHandler(proxyClientBootstrap, proxySslClientBootstrap, userAppProxyConfigService, PENDING_MAX));
            }
        });

        try {
            // 系统属性键与 custom-page 代理区分开，避免同机部署时互相覆盖端口
            String host = System.getProperty("userapp.http.proxy.host", httpProxyHost);
            String port = System.getProperty("userapp.http.proxy.port", httpProxyPort);
            // 同步等待绑定结果：端口被占用等失败要让启动直接报错，而不是带着不可用的代理静默运行
            httpServerBootstrap.bind(host, Integer.parseInt(port)).sync();
            logger.info("userapp http proxy server started at {}:{}", host, port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @PreDestroy
    private void destroy() {
        // 停机/重启会静默断掉所有在途连接（含 ws 隧道），留痕用于对时间点
        logger.info("userapp http proxy server stopping");
        serverBossGroup.shutdownGracefully();
        serverWorkerGroup.shutdownGracefully();
    }

}
