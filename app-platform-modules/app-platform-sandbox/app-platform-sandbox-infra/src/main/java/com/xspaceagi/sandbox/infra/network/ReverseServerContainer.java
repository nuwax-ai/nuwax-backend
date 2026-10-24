package com.xspaceagi.sandbox.infra.network;

import com.xspaceagi.sandbox.infra.config.ReverseServerProperties;
import com.xspaceagi.sandbox.infra.dao.entity.SandboxConfig;
import com.xspaceagi.sandbox.infra.dao.service.SandboxConfigService;
import com.xspaceagi.sandbox.infra.network.protocol.IdleCheckHandler;
import com.xspaceagi.sandbox.infra.network.protocol.ProxyMessageDecoder;
import com.xspaceagi.sandbox.infra.network.protocol.ProxyMessageEncoder;
import com.xspaceagi.system.sdk.service.INotificationRpcService;
import com.xspaceagi.system.spec.utils.RedisUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.BindException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReverseServerContainer {

    /**
     * max packet is 2M.
     */
    private static final int MAX_FRAME_LENGTH = 2 * 1024 * 1024;

    private static final int LENGTH_FIELD_OFFSET = 0;

    private static final int LENGTH_FIELD_LENGTH = 4;

    private static final int INITIAL_BYTES_TO_STRIP = 0;

    private static final int LENGTH_ADJUSTMENT = 0;

    private static final Logger logger = LoggerFactory.getLogger(ReverseServerContainer.class);

    private NioEventLoopGroup serverWorkerGroup;

    private NioEventLoopGroup serverBossGroup;

    private ServerBootstrap clientBootstrap;

    private volatile boolean started = false;

    @Resource
    private SandboxConfigService sandboxConfigService;

    @Getter
    private PortPoolManager portPoolManager;

    @Getter
    @Resource
    private ReverseServerProperties reverseServerProperties;

    @Resource
    private INotificationRpcService iNotificationRpcService;

    @Resource
    private RedisUtil redisUtil;

    private final Map<Integer, ChannelFuture> channelFutureMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        synchronized (ReverseServerContainer.class) {
            if (!started) {
                start();
                started = true;
            }
        }
    }

    /**
     * 最大HTTP内容长度 (8MB)
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;

    /**
     * 最大WebSocket帧长度 (10MB)
     */
    private static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    public void start() {
        serverBossGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        serverWorkerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        clientBootstrap = new ServerBootstrap();
        clientBootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) {
                int port = ch.localAddress().getPort();
                if (ProxyChannelManager.isVncPort(port)) {
                    // HTTP Codec - 解码HTTP请求
                    ch.pipeline().addLast("httpCodec", new HttpServerCodec());

                    // HTTP Object Aggregator - 将HTTP消息聚合为完整请求
                    ch.pipeline().addLast("httpAggregator", new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));

                    // Chunked Write Handler - 支持大文件传输
                    ch.pipeline().addLast("chunkedWrite", new ChunkedWriteHandler());

                    // WebSocket Server Protocol Handler - 处理握手和协议升级
                    ch.pipeline().addLast("wsProtocol", new WebSocketServerProtocolHandler(
                            "/websockify",
                            null,  // subprotocols
                            true,   // allowExtensions
                            MAX_FRAME_SIZE  // maxFrameSize
                    ));

                    // WebSocket Frame to ByteBuf Handler - WebSocket与TCP转换层
                    ch.pipeline().addLast("wsConverter", new WebSocketFrameToByteBufHandler());
                }
                ch.pipeline().addLast(new UserChannelHandler());
            }
        });

        initializeSSLTCPTransport(new SslContextCreator().initSSLContext());
        portPoolManager = new PortPoolManager(reverseServerProperties.getInner().getMinPort(), reverseServerProperties.getInner().getMaxPort(), 30);
    }

    private void initializeSSLTCPTransport(final SSLContext sslContext) {
        ServerBootstrap b = new ServerBootstrap();
        b.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();
                try {
                    pipeline.addLast("ssl", createSslHandler(sslContext));
                    ch.pipeline().addLast(new ProxyMessageDecoder(MAX_FRAME_LENGTH, LENGTH_FIELD_OFFSET, LENGTH_FIELD_LENGTH, LENGTH_ADJUSTMENT, INITIAL_BYTES_TO_STRIP));
                    ch.pipeline().addLast(new ProxyMessageEncoder());
                    ch.pipeline().addLast(new IdleCheckHandler(IdleCheckHandler.READ_IDLE_TIME, IdleCheckHandler.WRITE_IDLE_TIME, 0));
                    ch.pipeline().addLast(new ServerChannelHandler(sandboxConfigService, ReverseServerContainer.this));
                } catch (Throwable th) {
                    logger.error("Severe error during pipeline creation", th);
                    throw th;
                }
            }
        });
        try {
            // Bind and start to accept incoming connections.
            b.bind("0.0.0.0", reverseServerProperties.getOuter().getPort()).get();
            logger.info("proxy ssl server start on port {}", reverseServerProperties.getOuter().getPort());
        } catch (Exception ex) {
            logger.error("An interruptedException was caught while initializing server", ex);
        }
    }

    public boolean startUserPort(String host, Integer port) {
        Assert.notNull(port, "port must not be null");
        try {
            ChannelFuture future = clientBootstrap.bind(port);
            future.get();
            channelFutureMap.put(port, future);
            logger.info("bind user port {}", port);
        } catch (Exception ex) {
            // BindException表示该端口已经绑定过
            if (!(ex.getCause() instanceof BindException)) {
                logger.error("An interruptedException was caught while initializing server", ex);
                throw new RuntimeException(ex);
            }
            logger.warn("bind port {} error", port, ex);
            return false;
        }
        return true;
    }

    public void releaseUserPort(Integer port) {
        ChannelFuture channelFuture = channelFutureMap.get(port);
        if (channelFuture != null) {
            channelFuture.channel().close();
            channelFutureMap.remove(port);
            logger.info("release user port {}", port);
        }
    }

    @PreDestroy
    public void stop() {
        serverBossGroup.shutdownGracefully();
        serverWorkerGroup.shutdownGracefully();
    }

    private ChannelHandler createSslHandler(SSLContext sslContext) {
        SSLEngine sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(false);
        return new SslHandler(sslEngine);
    }

    public void userSandboxOnlineTouch(String key) {
        redisUtil.set("user:sandbox:status:" + key, String.valueOf(System.currentTimeMillis()), 60);
    }

    public void publishOnlineEvent(SandboxConfig sandboxConfig) {
        try {
            iNotificationRpcService.publishEvent(sandboxConfig.getUserId(), "sandbox_online", Map.of("sandboxId", sandboxConfig.getId()));
        } catch (Exception e) {
            // ignore
            logger.warn("publishOnlineEvent error", e);
        }
    }

    public Long getUserSandboxAliveTime(String key) {
        Object value = redisUtil.get("user:sandbox:status:" + key);
        if (value != null) {
            return Long.parseLong(value.toString());
        }
        return null;
    }

    public void userSandboxOfflineTouch(String key) {
        redisUtil.expire("user:sandbox:status:" + key, 0);
    }

    public void offlineClient(String key) {
        Channel cmdChannel = ProxyChannelManager.getCmdChannel(key);
        if (cmdChannel != null && cmdChannel.isActive()) {
            cmdChannel.close();
        }
    }
}
