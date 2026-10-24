package com.xspaceagi.agent.core.infra.speech.asr.volc;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.buffer.Unpooled;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class VolcAsrWebSocketTransport implements AutoCloseable {

    private final HttpClient httpClient;
    private final SpeechProperties.Volc volc;
    private final Consumer<byte[]> messageHandler;
    private final Runnable closeHandler;
    private final ExecutorService executor;
    private final String connectId;

    private volatile String logId;
    private volatile Disposable connection;
    private volatile Object outbound;
    private volatile boolean closed;

    public VolcAsrWebSocketTransport(HttpClient httpClient, SpeechProperties.Volc volc,
            Consumer<byte[]> messageHandler, Runnable closeHandler) {
        this.httpClient = httpClient;
        this.volc = volc;
        this.messageHandler = messageHandler;
        this.closeHandler = closeHandler == null ? () -> {
        } : closeHandler;
        this.connectId = UUID.randomUUID().toString();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "volc-asr-" + connectId.substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
    }

    public String connectId() {
        return connectId;
    }

    public String logId() {
        return logId;
    }

    public void ensureOpen() throws Exception {
        if (closed) {
            throw new IllegalStateException("Volc ASR transport closed");
        }
        if (connection != null && outbound != null) {
            return;
        }
        Future<?> future = executor.submit(this::openOnWorker);
        future.get(volc.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public void sendBinary(byte[] frame) throws Exception {
        Future<?> future = executor.submit(() -> sendBinaryOnWorker(frame));
        future.get(volc.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
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
        if (connection != null && outbound != null) {
            return;
        }
        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        connection = httpClient
                .headers(headers -> applyHeaders(headers, volc, connectId))
                .doOnResponse((response, connection) -> logId = response.responseHeaders().get("X-Tt-Logid"))
                .websocket(WebsocketClientSpec.builder().build())
                .uri(volc.getEndpoint())
                .handle((inbound, outbound) -> {
                    this.outbound = outbound;
                    readyLatch.countDown();
                    return inbound.receive()
                            .retain()
                            .asByteArray()
                            .doOnNext(bytes -> messageHandler.accept(bytes))
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
            if (!readyLatch.await(volc.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Volc ASR websocket handshake timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Volc ASR connect interrupted", ex);
        }
        if (errorRef.get() != null) {
            throw new IllegalStateException("Volc ASR websocket connect failed", errorRef.get());
        }
        if (outbound == null) {
            throw new IllegalStateException("Volc ASR websocket outbound not ready");
        }
    }

    private void sendBinaryOnWorker(byte[] frame) {
        if (closed || outbound == null) {
            throw new IllegalStateException("Volc ASR websocket not connected");
        }
        reactor.netty.http.websocket.WebsocketOutbound wsOutbound =
                (reactor.netty.http.websocket.WebsocketOutbound) outbound;
        wsOutbound.send(Mono.just(Unpooled.wrappedBuffer(frame)))
                .then()
                .block(Duration.ofMillis(volc.getSendTimeoutMs()));
    }

    static void applyHeaders(HttpHeaders headers, SpeechProperties.Volc volc, String connectId) {
        VolcAsrCredentials.requireConfigured(volc);
        headers.add("X-Api-Resource-Id", volc.getResourceId());
        headers.add("X-Api-Connect-Id", connectId);
        headers.add("X-Api-Request-Id", UUID.randomUUID().toString());
        headers.add("X-Api-Sequence", "-1");
        if (volc.hasApiKey()) {
            headers.add("X-Api-Key", volc.getApiKey());
        } else {
            headers.add("X-Api-App-Key", volc.getAppKey());
            headers.add("X-Api-Access-Key", volc.getAccessKey());
        }
    }
}
