package com.xspaceagi.modelproxy.infra.proxy;

import com.xspaceagi.modelproxy.infra.service.TokenLogService;
import com.xspaceagi.modelproxy.sdk.service.IModelApiProxyConfigService;
import com.xspaceagi.system.sdk.common.TraceContext;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.util.Map;
import java.util.Queue;

/**
 * 模型代理服务器
 */
@Slf4j
@Component
public class ModelProxyServer {

    public static AttributeKey<Channel> nextChannelAttributeKey = AttributeKey.newInstance("nextChannel");
    public static AttributeKey<String> accessKeyAttributeKey = AttributeKey.newInstance("accessKey");
    public static AttributeKey<String> backendModelAttributeKey = AttributeKey.newInstance("backendModel");
    public static AttributeKey<TraceContext> traceContextAttributeKey = AttributeKey.newInstance("traceContext");
    public static AttributeKey<String> backendUrlAttributeKey = AttributeKey.newInstance("backendUrl");
    public static AttributeKey<Queue<Map<String, String>>> logRequestQueueAttributeKey = AttributeKey.newInstance("logRequestQueue");

    @Value("${model-api-proxy.port:18086}")
    private int port;

    @Resource
    private IModelApiProxyConfigService modelApiProxyConfigService;

    @Resource
    private TokenLogService tokenLogService;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private SslContext sslContext;

    @PostConstruct
    public void start() throws Exception {

        sslContext = createSslContext();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("httpServerCodec", new HttpServerCodec());
                            p.addLast(new HttpProxyRequestHandler(ModelProxyServer.this, modelApiProxyConfigService));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();

            log.info("Model proxy server started on port {}", port);
        } catch (Exception e) {
            log.error("Failed to start model proxy server", e);
            shutdown();
            throw e;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Model proxy server stopped");
    }

    /**
     * 创建 HTTP 客户端 Bootstrap
     */
    public Bootstrap createClientBootstrap() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("httpClientCodec", new HttpClientCodec());
                        p.addLast(new BackendProxyHandler(tokenLogService));
                    }
                });
        return bootstrap;
    }

    /**
     * 创建 HTTPS 客户端 Bootstrap
     */
    public Bootstrap createHttpsClientBootstrap(String host, int port) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(sslContext.newHandler(ch.alloc(), host, port));
                        p.addLast("httpClientCodec", new HttpClientCodec());
                        p.addLast(new BackendProxyHandler(tokenLogService));
                    }
                });
        return bootstrap;
    }

    private io.netty.handler.ssl.SslContext createSslContext() {
        SslContext context;
        try {
            context = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
        return context;
    }
}
