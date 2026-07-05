package com.xspaceagi.sandbox.infra.network;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.alibaba.fastjson2.JSON;
import com.xspaceagi.sandbox.infra.dao.entity.SandboxConfig;
import com.xspaceagi.sandbox.infra.dao.service.SandboxConfigService;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxConfigValue;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxServerInfo;
import com.xspaceagi.sandbox.infra.network.protocol.Constants;
import com.xspaceagi.sandbox.infra.network.protocol.ProxyMessage;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.xspaceagi.sandbox.infra.network.ProxyChannelManager.*;


/**
 * @author fengfei
 */
public class ServerChannelHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private static final Logger logger = LoggerFactory.getLogger(ServerChannelHandler.class);

    private static final Set<String> authLockSet = new ConcurrentHashSet<>();

    private final SandboxConfigService sandboxConfigService;

    private final ReverseServerContainer reverseServerContainer;

    public ServerChannelHandler(SandboxConfigService sandboxConfigService, ReverseServerContainer reverseServerContainer) {
        this.sandboxConfigService = sandboxConfigService;
        this.reverseServerContainer = reverseServerContainer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage proxyMessage) throws Exception {
        logger.debug("ProxyMessage received {}", proxyMessage.getType());
        switch (proxyMessage.getType()) {
            case ProxyMessage.TYPE_HEARTBEAT:
                handleHeartbeatMessage(ctx, proxyMessage);
                break;
            case ProxyMessage.C_TYPE_AUTH:
                try {
                    if (!authLockSet.add(proxyMessage.getUri())) {
                        ctx.channel().close();
                        return;
                    }
                    handleAuthMessage(ctx, proxyMessage);
                } finally {
                    authLockSet.remove(proxyMessage.getUri());
                }
                break;
            case ProxyMessage.TYPE_CONNECT:
                handleConnectMessage(ctx, proxyMessage);
                break;
            case ProxyMessage.TYPE_DISCONNECT:
                handleDisconnectMessage(ctx, proxyMessage);
                break;
            case ProxyMessage.P_TYPE_TRANSFER:
                handleTransferMessage(ctx, proxyMessage);
                break;
            default:
                break;
        }
    }

    private void handleTransferMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        Channel userChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (userChannel != null) {
            ByteBuf buf = ctx.alloc().buffer(proxyMessage.getData().length);
            buf.writeBytes(proxyMessage.getData());
            userChannel.writeAndFlush(buf);
        }
    }

    private void handleDisconnectMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        String clientKey = ctx.channel().attr(Constants.CLIENT_KEY).get();

        // 代理连接没有连上服务器由控制连接发送用户端断开连接消息
        if (clientKey == null) {
            String userId = proxyMessage.getUri();
            Channel userChannel = ProxyChannelManager.removeUserChannelFromCmdChannel(ctx.channel(), userId);
            if (userChannel != null) {
                // 数据发送完成后再关闭连接，解决http1.0数据传输问题
                userChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }

        Channel cmdChannel = ProxyChannelManager.getCmdChannel(clientKey);
        if (cmdChannel == null) {
            logger.warn("ConnectMessage:error cmd channel key {}", ctx.channel().attr(Constants.CLIENT_KEY).get());
            return;
        }

        Channel userChannel = ProxyChannelManager.removeUserChannelFromCmdChannel(cmdChannel, ctx.channel().attr(Constants.USER_ID).get());
        if (userChannel != null) {
            // 数据发送完成后再关闭连接，解决http1.0数据传输问题
            userChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            ctx.channel().attr(Constants.NEXT_CHANNEL).set(null);
            ctx.channel().attr(Constants.CLIENT_KEY).set(null);
            ctx.channel().attr(Constants.USER_ID).set(null);
        }
    }

    private void handleConnectMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        String uri = proxyMessage.getUri();
        if (uri == null) {
            ctx.channel().close();
            logger.warn("ConnectMessage:null uri");
            return;
        }

        String[] tokens = uri.split("@");
        if (tokens.length != 2) {
            ctx.channel().close();
            logger.warn("ConnectMessage:error uri");
            return;
        }

        Channel cmdChannel = ProxyChannelManager.getCmdChannel(tokens[1]);
        if (cmdChannel == null) {
            ctx.channel().close();
            logger.warn("ConnectMessage:error cmd channel key {}", tokens[1]);
            return;
        }

        Channel userChannel = ProxyChannelManager.getUserChannel(cmdChannel, tokens[0]);
        if (userChannel != null) {
            ctx.channel().attr(Constants.USER_ID).set(tokens[0]);
            ctx.channel().attr(Constants.CLIENT_KEY).set(tokens[1]);
            ctx.channel().attr(Constants.NEXT_CHANNEL).set(userChannel);
            userChannel.attr(Constants.NEXT_CHANNEL).set(ctx.channel());

            // 读取http代理 TempLinkHttpProxyHandler 连接时的缓存数据
            Queue<Object> objects = userChannel.attr(Constants.MESSAGE_QUEUE).get();
            if (objects != null) {
                while (!objects.isEmpty()) {
                    Object poll = objects.poll();
                    if (poll instanceof ByteBuf buf) {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        String userId = ProxyChannelManager.getUserChannelUserId(userChannel);
                        ProxyMessage proxyMessage0 = new ProxyMessage();
                        proxyMessage0.setType(ProxyMessage.P_TYPE_TRANSFER);
                        proxyMessage0.setUri(userId);
                        proxyMessage0.setData(bytes);
                        ctx.channel().writeAndFlush(proxyMessage0);
                    }
                    ReferenceCountUtil.release(poll);
                }
            }
            // 代理客户端与后端服务器连接成功，修改用户连接为可读状态
            userChannel.config().setOption(ChannelOption.AUTO_READ, true);
        }
    }

    private void handleHeartbeatMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        ProxyMessage heartbeatMessage = new ProxyMessage();
        heartbeatMessage.setSerialNumber(heartbeatMessage.getSerialNumber());
        heartbeatMessage.setType(ProxyMessage.TYPE_HEARTBEAT);
        logger.debug("response heartbeat message {}", ctx.channel());
        ctx.channel().writeAndFlush(heartbeatMessage);
        String clientKey = ctx.channel().attr(CHANNEL_CLIENT_KEY).get();
        Map<String, Channel> channelMap = ctx.channel().attr(USER_CHANNELS).get();
        if (clientKey != null && channelMap != null) {
            reverseServerContainer.userSandboxOnlineTouch(clientKey);
        }
    }

    private void handleAuthMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        String clientKey = proxyMessage.getUri();
        SandboxConfig sandboxConfig = sandboxConfigService.queryUserConfigByKey(clientKey);
        if (sandboxConfig == null) {
            logger.warn("error clientKey {}, {}", clientKey, ctx.channel());
            ctx.channel().close();
            return;
        }

        Channel channel = ProxyChannelManager.getCmdChannel(clientKey);
        if (channel != null) {
            logger.warn("exist channel for key {}, {}", clientKey, channel);
            ctx.channel().close();
            channel.close();
            return;
        }
        SandboxConfigValue configValue = JSON.parseObject(sandboxConfig.getConfigValue(), SandboxConfigValue.class);
        if (configValue == null) {
            logger.warn("error configValue for key {}, {}", clientKey, ctx.channel());
            ctx.channel().close();
            return;
        }
        Integer agentInnerPort = null;
        Integer vncInnerPort = null;
        Integer fileServerPort = null;
        try {
            agentInnerPort = acquireUserPort();
            vncInnerPort = acquireUserPort();
            fileServerPort = acquireUserPort();

            URL url;
            try {
                url = new URL(configValue.getHostWithScheme());
            } catch (MalformedURLException e) {
                logger.warn("error get url for key {}, {}", clientKey, ctx.channel(), e);
                ctx.channel().close();
                return;
            }

            ProxyChannelManager.addPortLanInfo(agentInnerPort, url.getHost() + ":" + configValue.getAgentPort(), false);
            ProxyChannelManager.addPortLanInfo(vncInnerPort, url.getHost() + ":" + configValue.getVncPort(), false);//后续支持直连vnc
            ProxyChannelManager.addPortLanInfo(fileServerPort, url.getHost() + ":" + configValue.getFileServerPort(), false);

            String innerHost = reverseServerContainer.getReverseServerProperties().getInner().getServiceHost();
            SandboxServerInfo sandboxServerInfo = new SandboxServerInfo();
            sandboxServerInfo.setScheme(url.getProtocol());
            sandboxServerInfo.setHost(innerHost);
            sandboxServerInfo.setAgentPort(agentInnerPort);
            sandboxServerInfo.setVncPort(vncInnerPort);
            sandboxServerInfo.setFileServerPort(fileServerPort);
            sandboxServerInfo.setApiKey(configValue.getApiKey());
            SandboxConfig sandboxConfigUpdate = new SandboxConfig();
            sandboxConfigUpdate.setId(sandboxConfig.getId());
            sandboxConfigUpdate.setServerInfo(JSON.toJSONString(sandboxServerInfo));

            TenantFunctions.runWithIgnoreCheck(() -> sandboxConfigService.updateById(sandboxConfigUpdate));

            List<Integer> ports = List.of(agentInnerPort, vncInnerPort, fileServerPort);
            logger.info("set port => channel, {}, {}, {}", clientKey, ports, ctx.channel());
            ProxyChannelManager.addCmdChannel(ports, clientKey, ctx.channel());
            reverseServerContainer.userSandboxOnlineTouch(clientKey);
        } catch (Exception e) {
            if (agentInnerPort != null) {
                reverseServerContainer.getPortPoolManager().release(agentInnerPort);
            }
            if (vncInnerPort != null) {
                reverseServerContainer.getPortPoolManager().release(vncInnerPort);
            }
            if (fileServerPort != null) {
                reverseServerContainer.getPortPoolManager().release(fileServerPort);
            }
            logger.warn("error get port for key {}, {}", clientKey, ctx.channel(), e);
            ctx.channel().close();
        }
    }

    // acquire user port
    private Integer acquireUserPort() throws Exception {
        Integer port = null;
        int i = 0;
        while (i < 10) {
            port = reverseServerContainer.getPortPoolManager().borrow();
            boolean bind = reverseServerContainer.startUserPort(reverseServerContainer.getReverseServerProperties().getInner().getBindHost(), port);
            if (!bind) {
                logger.warn("port {} bind failed", port);
                reverseServerContainer.getPortPoolManager().release(port);
                port = null;
            } else {
                break;
            }
            i++;
        }
        if (port == null) {
            logger.warn("acquire port failed");
            throw new RuntimeException("acquire port failed");
        }
        return port;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel userChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (userChannel != null) {
            userChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }

        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel userChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (userChannel != null && userChannel.isActive()) {
            String clientKey = ctx.channel().attr(Constants.CLIENT_KEY).get();
            String userId = ctx.channel().attr(Constants.USER_ID).get();
            Channel cmdChannel = ProxyChannelManager.getCmdChannel(clientKey);
            if (cmdChannel != null) {
                ProxyChannelManager.removeUserChannelFromCmdChannel(cmdChannel, userId);
            } else {
                logger.warn("null cmdChannel, clientKey is {}", clientKey);
            }

            // 数据发送完成后再关闭连接，解决http1.0数据传输问题
            userChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        } else {
            Map<String, Channel> stringChannelMap = ctx.channel().attr(USER_CHANNELS).get();
            List<Integer> ports = ctx.channel().attr(CHANNEL_PORT).get();
            if (ports != null) {
                for (int port : ports) {
                    ProxyChannelManager.removePortLanInfo(port);
                    // 如果之前有连接在使用，避免客户端重试导致混乱，端口进入冷却期
                    if (stringChannelMap != null && !stringChannelMap.isEmpty()) {
                        reverseServerContainer.getPortPoolManager().release(port);
                    } else {
                        reverseServerContainer.getPortPoolManager().releaseImmediately(port);
                    }
                    reverseServerContainer.releaseUserPort(port);
                }
            }
            String clientKey = ctx.channel().attr(CHANNEL_CLIENT_KEY).get();
            if (clientKey != null) {
                try {
                    reverseServerContainer.userSandboxOfflineTouch(clientKey);
                } catch (Exception e) {
                    logger.warn("userSandboxOfflineTouch error", e);
                }
            }
            ProxyChannelManager.removeCmdChannel(ctx.channel());
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("exception caught", cause);
        super.exceptionCaught(ctx, cause);
    }
}