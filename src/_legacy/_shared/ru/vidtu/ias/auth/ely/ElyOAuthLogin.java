// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.auth.ely;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single-use loopback callback on the player's PC, not a remote web server. */
public final class ElyOAuthLogin implements AutoCloseable {
    private static final String CALLBACK = "/deobso/ely/callback";
    private final HttpServer server;
    private final ExecutorService callbacks;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<String> code = new CompletableFuture<>();
    private final String state = ElyOAuth.randomSecret();
    private final String verifier = ElyOAuth.randomSecret();
    private final URI redirect;
    private final CompletableFuture<ElyOAuth.Session> result;

    @FunctionalInterface
    interface CodeExchange {
        ElyOAuth.Session exchange(String code, URI redirect, String verifier) throws IOException;
    }

    public ElyOAuthLogin(Executor networkExecutor) throws IOException {
        this(networkExecutor, ElyOAuth::exchange, TimeUnit.MINUTES.toMillis(5));
    }

    ElyOAuthLogin(Executor networkExecutor, CodeExchange exchange, long timeoutMillis) throws IOException {
        // Bind strictly to IPv4 loopback. Desktop clients at Ely.by permit dynamic loopback ports.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        callbacks = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "deobso-Ely-OAuth-callback");
            thread.setDaemon(true);
            return thread;
        });
        redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK);
        server.setExecutor(callbacks);
        server.createContext(CALLBACK, this::callback);
        result = code.thenApplyAsync(value -> {
            if (closed.get()) throw new CancellationException();
            try { return exchange.exchange(value, redirect, verifier); }
            catch (IOException ex) { throw new CompletionException(ex); }
        }, networkExecutor);
        server.start();
        // The code wait is bounded even when the browser is closed without returning.
        code.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((value, error) -> stopListener());
    }

    public URI authorizationUri() { return ElyOAuth.authorizationUri(redirect, state, verifier); }
    public CompletableFuture<ElyOAuth.Session> result() { return result; }

    private void callback(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || !CALLBACK.equals(exchange.getRequestURI().getPath())) {
                reply(exchange, 404, "Not found"); return;
            }
            if (!redirect.getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 400, "Invalid callback host"); return;
            }
            Map<String, String> query;
            try { query = parseQuery(exchange.getRequestURI().getRawQuery()); }
            catch (IllegalArgumentException ex) { reply(exchange, 400, "Invalid callback"); return; }
            String received = query.get("state");
            if (received == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 400, "Invalid authorization state. Return to the original browser tab."); return;
            }
            if (closed.get() || code.isDone()) { reply(exchange, 409, "Authorization already finished"); return; }
            if (query.containsKey("error")) {
                reply(exchange, 200, "Authorization was declined. Return to Minecraft and try again.");
                code.completeExceptionally(new IOException("Ely.by authorization declined"));
                return;
            }
            String value = query.get("code");
            if (value == null || value.isBlank() || value.length() > 8192) {
                reply(exchange, 400, "Missing authorization code"); return;
            }
            reply(exchange, 200, "Authorization received. Return to Minecraft to finish adding your account.");
            code.complete(value);
        } finally { exchange.close(); }
    }

    static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.length() > 16384) throw new IllegalArgumentException("Invalid callback query");
        Map<String, String> result = new HashMap<>();
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Malformed callback query");
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate callback parameter");
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void stopListener() {
        server.stop(0);
        callbacks.shutdownNow();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        code.cancel(false);
        result.cancel(false);
        stopListener();
    }

    @Override public String toString() { return "ElyOAuthLogin{credentials=REDACTED}"; }
}
