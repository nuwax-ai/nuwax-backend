package com.xspaceagi.agent.core.infra.speech.vendor.dashscope;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class DashScopeWebSocketTransport implements AutoCloseable {

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String workspaceId;
    private final int connectTimeoutMs;
    private final int sendTimeoutMs;
    private final Consumer<String> textHandler;
    private final Consumer<byte[]> binaryHandler;
    private final Runnable closeHandler;
    private final ExecutorService executor;
    private final String connectId;

    private volatile Disposable connection;
    private volatile Object outbound;
    private volatile boolean closed;
    private final Object openLock = new Object();

    public DashScopeWebSocketTransport(HttpClient httpClient, String endpoint, String apiKey, String workspaceId,
            int connectTimeoutMs, int sendTimeoutMs, Consumer<String> textHandler, Consumer<byte[]> binaryHandler,
            Runnable closeHandler) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.workspaceId = workspaceId;
        this.connectTimeoutMs = connectTimeoutMs;
        this.sendTimeoutMs = sendTimeoutMs;
        this.textHandler = textHandler;
        this.binaryHandler = binaryHandler == null ? bytes -> {
        } : binaryHandler;
        this.closeHandler = closeHandler == null ? () -> {
        } : closeHandler;
        this.connectId = UUID.randomUUID().toString();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "dashscope-ws-" + connectId.substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
    }

    public String connectId() {
        return connectId;
    }

    public void ensureOpen() throws Exception {
        if (closed) {
            throw new IllegalStateException("DashScope transport closed");
        }
        if (connection != null && outbound != null) {
            return;
        }
        Future<?> future = executor.submit(this::openOnWorker);
        future.get(connectTimeoutMs, TimeUnit.MILLISECONDS);
    }

    public void sendText(String text) throws Exception {
        Future<?> future = executor.submit(() -> sendTextOnWorker(text));
        future.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }

    public void sendBinary(byte[] frame) throws Exception {
        Future<?> future = executor.submit(() -> sendBinaryOnWorker(frame));
        future.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        Disposable current = connection;
        connection = null;
        outbound = null;
        if (current != null) {
            current.dispose();
        }
        executor.shutdownNow();
    }

    private void openOnWorker() {
        synchronized (openLock) {
            if (connection != null && outbound != null) {
                return;
            }
            CountDownLatch readyLatch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            connection = httpClient
                    .headers(headers -> applyHeaders(headers, apiKey, workspaceId))
                    .websocket(WebsocketClientSpec.builder().build())
                    .uri(endpoint)
                    .handle((inbound, outbound) -> {
                        this.outbound = outbound;
                        readyLatch.countDown();
                        return inbound.receiveFrames()
                                .doOnNext(this::dispatchFrame)
                                .doFinally(signal -> closeHandler.run())
                                .then();
                    })
                    .subscribe(
                            unused -> {
                            },
                            error -> {
                                errorRef.set(error);
                                readyLatch.countDown();
                            });

            try {
                if (!readyLatch.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("DashScope websocket handshake timeout");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DashScope connect interrupted", ex);
            }
            if (errorRef.get() != null) {
                throw new IllegalStateException("DashScope websocket connect failed", errorRef.get());
            }
            if (outbound == null) {
                throw new IllegalStateException("DashScope websocket outbound not ready");
            }
        }
    }

    private void dispatchFrame(WebSocketFrame frame) {
        // Do not release here: receiveFrames() already releases after onNext.
        // Manual release causes IllegalReferenceCountException and drops the socket.
        if (frame instanceof TextWebSocketFrame textFrame) {
            textHandler.accept(textFrame.text());
        } else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
            byte[] bytes = new byte[binaryFrame.content().readableBytes()];
            binaryFrame.content().readBytes(bytes);
            binaryHandler.accept(bytes);
        }
    }

    private void sendTextOnWorker(String text) {
        if (closed || outbound == null) {
            throw new IllegalStateException("DashScope websocket not connected");
        }
        reactor.netty.http.websocket.WebsocketOutbound wsOutbound =
                (reactor.netty.http.websocket.WebsocketOutbound) outbound;
        wsOutbound.sendObject(Mono.just(new TextWebSocketFrame(text)))
                .then()
                .block(Duration.ofMillis(sendTimeoutMs));
    }

    private void sendBinaryOnWorker(byte[] frame) {
        if (closed || outbound == null) {
            throw new IllegalStateException("DashScope websocket not connected");
        }
        reactor.netty.http.websocket.WebsocketOutbound wsOutbound =
                (reactor.netty.http.websocket.WebsocketOutbound) outbound;
        // Must use sendObject(BinaryWebSocketFrame), not send(ByteBuf):
        // WebsocketOutbound.send() closes the connection when the publisher completes.
        wsOutbound.sendObject(Mono.just(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame))))
                .then()
                .block(Duration.ofMillis(sendTimeoutMs));
    }

    static void applyHeaders(HttpHeaders headers, String apiKey, String workspaceId) {
        DashScopeCredentials.requireApiKey(apiKey);
        headers.add("Authorization", "Bearer " + apiKey.trim());
        if (workspaceId != null && !workspaceId.isBlank()) {
            headers.add("X-DashScope-WorkSpace", workspaceId.trim());
        }
    }
}
