package com.xspaceagi.mcp.infra.server;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.mcp.infra.client.McpAsyncClientWrapper;
import com.xspaceagi.mcp.infra.rpc.McpDeployRpcService;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpExecuteRequest;
import com.xspaceagi.mcp.sdk.dto.McpImageContent;
import com.xspaceagi.mcp.sdk.dto.McpLogContent;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpContentTypeEnum;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.TimeWheel;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.util.Assert;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WebMvcSseServerTransportProvider implements McpServerTransportProvider {
    private static final Logger logger = LoggerFactory.getLogger(WebMvcSseServerTransportProvider.class);
    private final ObjectMapper objectMapper;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final String baseUrl;
    private final RouterFunction<ServerResponse> routerFunction;
    private final ConcurrentHashMap<String, McpServerSession> sessions;
    private volatile boolean isClosing;

    @Resource
    private IMcpApiService mcpApiService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private McpDeployRpcService deployRpcService;

    @Resource
    private TimeWheel timeWheel;

    public WebMvcSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
        this(objectMapper, "", messageEndpoint, sseEndpoint);
    }

    public WebMvcSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint, String sseEndpoint) {
        this.sessions = new ConcurrentHashMap();
        this.isClosing = false;
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        Assert.notNull(baseUrl, "Message base URL must not be null");
        Assert.notNull(messageEndpoint, "Message endpoint must not be null");
        Assert.notNull(sseEndpoint, "SSE endpoint must not be null");
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.messageEndpoint = messageEndpoint;
        this.sseEndpoint = sseEndpoint;
        this.routerFunction = RouterFunctions.route().GET(this.sseEndpoint, this::handleSseConnection).POST(this.messageEndpoint, this::handleMessage).build();//.POST(this.messageEndpoint, this::handleMessage)
    }

    public Mono<Void> notifyClients(String method, Object params) {
        if (this.sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        } else {
            logger.debug("Attempting to broadcast message to {} active sessions", this.sessions.size());
            return Flux.fromIterable(this.sessions.values()).flatMap((session) -> {
                return session.sendNotification(method, params).doOnError((e) -> {
                    logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                }).onErrorComplete();
            }).then();
        }
    }

    public Mono<Void> closeGracefully() {
        return Flux.fromIterable(this.sessions.values()).doFirst(() -> {
            this.isClosing = true;
            logger.debug("Initiating graceful shutdown with {} active sessions", this.sessions.size());
        }).flatMap(McpServerSession::closeGracefully).then().doOnSuccess((v) -> {
            logger.debug("Graceful shutdown completed");
        });
    }

    public RouterFunction<ServerResponse> getRouterFunction() {
        return this.routerFunction;
    }

    private ServerResponse handleSseConnection(ServerRequest request) {
        if (this.isClosing) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
        } else {
            String ak = request.param("ak").orElse(null);
            UserAccessKeyDto userAccessKeyDto = this.userAccessKeyApiService.queryAccessKey(ak);
            if (userAccessKeyDto == null || userAccessKeyDto.getTargetType() != UserAccessKeyDto.AKTargetType.Mcp) {
                return ServerResponse.status(HttpStatus.UNAUTHORIZED).body(new McpError("Invalid ak"));
            }
            McpDto deployedMcp = mcpApiService.getDeployedMcp(Long.parseLong(userAccessKeyDto.getTargetId()), null);
            if (deployedMcp == null) {
                return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(new McpError("Mcp server is unavailable"));
            }
            String sessionId = UUID.randomUUID().toString();
            logger.info("Creating new SSE connection for session: {}, mcp id: {}, mcp name: {}", sessionId, deployedMcp.getId(), deployedMcp.getName());

            try {
                return ServerResponse.sse((sseBuilder) -> {
                    sseBuilder.onComplete(() -> {
                        logger.debug("SSE connection completed for session: {}", sessionId);
                        this.sessions.remove(sessionId);
                    });
                    sseBuilder.onTimeout(() -> {
                        logger.debug("SSE connection timed out for session: {}", sessionId);
                        this.sessions.remove(sessionId);
                    });
                    sseBuilder.onError(throwable -> {
                        logger.error("SSE connection error for session: {}", sessionId, throwable);
                        this.sessions.remove(sessionId);
                    });
                    WebMvcMcpSessionTransport sessionTransport = new WebMvcMcpSessionTransport(sessionId, sseBuilder);
                    McpAsyncServer mcpAsyncServer = buildMcpAsyncServer(userAccessKeyDto, deployedMcp, sessionId);
                    McpServerSession session = mcpAsyncServer.getSessionFactory().create(sessionTransport);
                    session.setMcpAsyncServer(mcpAsyncServer);
                    this.sessions.put(sessionId, session);
                    session.startObserving(timeWheel, (s) -> {
                        logger.debug("SSE connection not alive for session: {}", sessionId);
                        McpServerSession removeSession = this.sessions.remove(sessionId);
                        if (removeSession != null) {
                            removeSession.close();
                        }
                        mcpAsyncServer.close();
                    });

                    try {
                        sseBuilder.id(sessionId).event("endpoint").data(this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId);
                    } catch (Exception var6) {
                        logger.error("Failed to send initial endpoint event: {}", var6.getMessage());
                        sseBuilder.error(var6);
                    }

                }, Duration.ZERO);
            } catch (Exception var4) {
                logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, var4.getMessage());
                this.sessions.remove(sessionId);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
    }

    private McpAsyncServer buildMcpAsyncServer(UserAccessKeyDto userAccessKeyDto, McpDto deployedMcp, String sessionId) {
        //代理mcp
        if (deployedMcp.getInstallType() != InstallTypeEnum.COMPONENT) {
            Mono<McpAsyncClientWrapper> mcpAsyncClientMono;
            if (deployedMcp.getInstallType() == InstallTypeEnum.SSE) {
                mcpAsyncClientMono = deployRpcService.getMcpAsyncClientForSSE(deployedMcp.getId().toString(), "mcp-server-" + deployedMcp.getId(), deployedMcp.getDeployedConfig().getServerConfig());
            } else {
                mcpAsyncClientMono = deployRpcService.getMcpAsyncClient(deployedMcp.getId().toString(), "mcp-server-" + deployedMcp.getId(), deployedMcp.getDeployedConfig().getServerConfig());
            }
            McpAsyncServer mcpAsyncServer = McpServer.async(this).build(true);
            mcpAsyncServer.setMcpProxyAsyncClientMono(mcpAsyncClientMono);
            return mcpAsyncServer;
        }

        //平台组件mcp
        UserDto userDto = userApplicationService.queryById(userAccessKeyDto.getUserId());
        McpServer.AsyncSpecification capabilities = McpServer.async(this)
                .serverInfo(deployedMcp.getServerName() == null ? deployedMcp.getName() : deployedMcp.getServerName(), "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .resources(false, false)     // 启用资源支持
                        .tools(true)         // 启用工具支持
                        .prompts(false)       // 启用提示支持
                        .logging()           // 启用日志支持
                        .build());
        //平台组件只支持tools
        List<McpServerFeatures.AsyncToolSpecification> asyncToolSpecifications = new ArrayList<>();
        deployedMcp.getDeployedConfig().getTools().forEach(tool -> {
            var aSyncToolSpecification = new McpServerFeatures.AsyncToolSpecification(new McpSchema.Tool(tool.getName(), tool.getDescription(), tool.getJsonSchema()),
                    (exchange, args) -> {
                        McpExecuteRequest mcpExecuteRequest = McpExecuteRequest.builder()
                                .requestId(UUID.randomUUID().toString().replace("-", ""))
                                .sessionId(sessionId)
                                .mcpDto(deployedMcp)
                                .executeType(McpExecuteRequest.ExecuteTypeEnum.TOOL)
                                .name(tool.getName())
                                .keepAlive(true)
                                .user(userDto)
                                .params(args)
                                .build();
                        return Mono.create(sink -> mcpApiService.execute(mcpExecuteRequest).doOnNext(mcpExecuteOutput -> {
                            if (!mcpExecuteOutput.isSuccess()) {
                                sink.error(new BizException(mcpExecuteOutput.getMessage()));
                            } else if (CollectionUtils.isNotEmpty(mcpExecuteOutput.getResult()) && !(mcpExecuteOutput.getResult().get(0) instanceof McpLogContent)) {
                                sink.success(new McpSchema.CallToolResult(mcpExecuteOutput.getResult().stream().map(mcpContent -> {
                                    if (mcpContent.getType() == McpContentTypeEnum.TEXT) {
                                        return new McpSchema.TextContent(mcpContent.getData());
                                    }
                                    if (mcpContent.getType() == McpContentTypeEnum.IMAGE) {
                                        McpImageContent mcpImageContent = (McpImageContent) mcpContent;
                                        return new McpSchema.ImageContent(List.of(McpSchema.Role.ASSISTANT), mcpImageContent.getPriority(), mcpImageContent.getData(), mcpImageContent.getMimeType());
                                    }
                                    return null;
                                }).collect(Collectors.toList()), false));
                            } else {
                                //LOG
                                if (CollectionUtils.isNotEmpty(mcpExecuteOutput.getResult()) && (mcpExecuteOutput.getResult().get(0) instanceof McpLogContent)) {
                                    exchange.loggingNotification( // Use the exchange to send log messages
                                                    McpSchema.LoggingMessageNotification.builder()
                                                            .level(McpSchema.LoggingLevel.DEBUG)
                                                            .logger(tool.getName())
                                                            .data(JSON.toJSONString(mcpExecuteOutput.getResult()))
                                                            .build())
                                            .subscribe();
                                }
                            }
                        }).doOnError(sink::error).subscribe());
                    }
            );
            asyncToolSpecifications.add(aSyncToolSpecification);
        });

        return capabilities.tools(asyncToolSpecifications).build(false);
    }

    private ServerResponse handleMessage(ServerRequest request) {//改造成异步
        return ServerResponse.async(Mono.create(sink -> {
            if (this.isClosing) {
                sink.success(ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down"));
            } else if (!request.param("sessionId").isPresent()) {
                sink.success(ServerResponse.badRequest().body(new McpError("Session ID missing in message endpoint")));
            } else {
                String sessionId = request.param("sessionId").get();
                McpServerSession session = this.sessions.get(sessionId);
                if (session == null) {
                    logger.debug("Session not found: {}", sessionId);
                    sink.success(ServerResponse.status(HttpStatus.NOT_FOUND).body(new McpError("Session not found: " + sessionId)));
                } else {
                    try {
                        String body = request.body(String.class);
                        logger.info("Received mcp message: {}, sessionId :{}", body, sessionId);
                        McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, body);
                        session.handle(message)
                                .onErrorResume(error -> {
                                    logger.error("onErrorResume handling, mcp message: {}, err {}", message, error.getMessage());
                                    return Mono.just(new McpError(error)).then();
                                })
                                .doOnSuccess(res -> {
                                    logger.debug("Sent mcp message: {}, sessionId :{}", message, sessionId);
                                    sink.success(ServerResponse.ok().build());
                                })
                                .doOnError(error -> {
                                    logger.error("Error handling, mcp message: {}, err {}", message, error.getMessage());
                                    sink.success(ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(error.getMessage())));
                                })
                                .subscribe();
                    } catch (IOException | IllegalArgumentException var6) {
                        logger.error("Failed to deserialize message: {}", var6.getMessage());
                        sink.success(ServerResponse.badRequest().body(new McpError("Invalid message format")));
                    } catch (Exception var7) {
                        logger.error("Error handling message: {}", var7.getMessage());
                        sink.success(ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(var7.getMessage())));
                    }
                }
            }
        }), Duration.ofMinutes(30));
    }

    private class WebMvcMcpSessionTransport implements McpServerTransport {
        private final String sessionId;
        private final ServerResponse.SseBuilder sseBuilder;

        WebMvcMcpSessionTransport(String sessionId, ServerResponse.SseBuilder sseBuilder) {
            this.sessionId = sessionId;
            this.sseBuilder = sseBuilder;
            WebMvcSseServerTransportProvider.logger.debug("Session transport {} initialized with SSE builder", sessionId);
        }

        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String jsonText = WebMvcSseServerTransportProvider.this.objectMapper.writeValueAsString(message);
                    this.sseBuilder.id(this.sessionId).event("message").data(jsonText);
                    logger.info("Sending message to session {}: {}", this.sessionId, jsonText);
                    WebMvcSseServerTransportProvider.logger.debug("Message sent to session {}", this.sessionId);
                } catch (Exception var3) {
                    WebMvcSseServerTransportProvider.logger.error("Failed to send message to session {}: {}", this.sessionId, var3.getMessage());
                    this.sseBuilder.error(var3);
                }

            });
        }

        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return WebMvcSseServerTransportProvider.this.objectMapper.convertValue(data, typeRef);
        }

        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                WebMvcSseServerTransportProvider.logger.debug("Closing session transport: {}", this.sessionId);

                try {
                    this.sseBuilder.complete();
                    WebMvcSseServerTransportProvider.logger.debug("Successfully completed SSE builder for session {}", this.sessionId);
                } catch (Exception var2) {
                    WebMvcSseServerTransportProvider.logger.warn("Failed to complete SSE builder for session {}: {}", this.sessionId, var2.getMessage());
                }

            });
        }

        public void close() {
            try {
                this.sseBuilder.complete();
                WebMvcSseServerTransportProvider.logger.debug("Successfully completed SSE builder for session {}", this.sessionId);
            } catch (Exception var2) {
                WebMvcSseServerTransportProvider.logger.warn("Failed to complete SSE builder for session {}: {}", this.sessionId, var2.getMessage());
            }

        }
    }
}
