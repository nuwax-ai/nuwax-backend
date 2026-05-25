package com.xspaceagi.custompage.infra.proxy;

import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.custompage.infra.service.ProxyAuthService;
import com.xspaceagi.custompage.infra.service.ProxyConfigService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
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
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;

@Component
public class ProxyServerContainer {

    public static AttributeKey<Channel> nextChannelAttributeKey = AttributeKey.newInstance("nextChannelAttributeKey");

    public static AttributeKey<String> tokenKey = AttributeKey.newInstance("tokenKey");

    private static Logger logger = LoggerFactory.getLogger(ProxyServerContainer.class);

    private volatile boolean started = false;
    private NioEventLoopGroup serverBossGroup;
    private NioEventLoopGroup serverWorkerGroup;
    @Resource
    private ProxyConfigService proxyConfigService;
    @Resource
    private ProxyAuthService proxyAuthService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;
    @Value("${custom-page.http-proxy.host:0.0.0.0}")
    private String httpProxyHost;

    @Value("${custom-page.http-proxy.port:18082}")
    private String httpProxyPort;

    @PostConstruct
    public void init() {
        synchronized (ProxyServerContainer.class) {
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
        proxyClientBootstrap.group(serverWorkerGroup).handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) {
                ch.pipeline().addLast("httpClientCodec", new HttpClientCodec());
                ch.pipeline().addLast(new HttpProxyResponseHandler());
            }
        });


        Bootstrap proxySslClientBootstrap = new Bootstrap();
        proxySslClientBootstrap.channel(NioSocketChannel.class);
        proxySslClientBootstrap.group(serverWorkerGroup).handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(context.newHandler(ch.alloc()));
                ch.pipeline().addLast("httpClientCodec", new HttpClientCodec());
                ch.pipeline().addLast(new HttpProxyResponseHandler());
            }
        });

        ServerBootstrap httpServerBootstrap = new ServerBootstrap();
        httpServerBootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                logger.error("exceptionCaught", cause);
                super.exceptionCaught(ctx, cause);
            }

            @Override
            public void initChannel(SocketChannel ch) {
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast("httpServerCodec", new HttpServerCodec());
                pipeline.addLast("httpProxyRequestHandler", new HttpProxyRequestHandler(proxyClientBootstrap, proxySslClientBootstrap, proxyConfigService, proxyAuthService, publishApplicationService, userDataPermissionRpcService));
            }
        });

        try {
            String host = System.getProperty("http.proxy.host", httpProxyHost);
            String port = System.getProperty("http.proxy.port", httpProxyPort);
            httpServerBootstrap.bind(host, Integer.parseInt(port));
            logger.info("http proxy server started at {}:{}", host, port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @PreDestroy
    private void destroy() {
        serverBossGroup.shutdownGracefully();
        serverWorkerGroup.shutdownGracefully();
    }

}
