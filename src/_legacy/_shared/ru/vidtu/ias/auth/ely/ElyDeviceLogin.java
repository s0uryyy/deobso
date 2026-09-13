// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.auth.ely;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** RFC 8628: no loopback listener and no browser-side PKCE parameter forwarding. */
public final class ElyDeviceLogin implements AutoCloseable {
    interface Transport {
        ElyOAuth.Device begin() throws IOException;
        ElyOAuth.Session poll(String deviceCode) throws IOException;
    }
    private final Transport transport;
    private final Consumer<ElyOAuth.Device> ready;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<ElyOAuth.Session> result = new CompletableFuture<>();
    private final long millisPerSecond;
    private ElyOAuth.Device device;
    private long deadline;
    private int interval;

    public ElyDeviceLogin(Consumer<ElyOAuth.Device> ready) {
        this(new Transport() {
            public ElyOAuth.Device begin() throws IOException { return ElyOAuth.beginDevice(); }
            public ElyOAuth.Session poll(String code) throws IOException { return ElyOAuth.pollDevice(code); }
        }, ready, 1000);
    }

    ElyDeviceLogin(Transport transport, Consumer<ElyOAuth.Device> ready, long millisPerSecond) {
        this.transport = transport;
        this.ready = ready;
        this.millisPerSecond = millisPerSecond;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "deobso-Ely-device-login");
            thread.setDaemon(true);
            return thread;
        });
        result.whenComplete((session, error) -> worker.shutdownNow());
        worker.execute(this::begin);
    }

    public CompletableFuture<ElyOAuth.Session> result() { return result; }
    private void begin() {
        try {
            device = transport.begin();
            if (closed.get()) return;
            interval = device.interval();
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(device.expiresIn() * millisPerSecond);
            ready.accept(device);
            schedule();
        } catch (Exception ex) { fail(ex); }
    }
    private void schedule() {
        if (closed.get() || result.isDone()) return;
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) { fail(new ElyOAuth.ApiException("expired_token")); return; }
        try { worker.schedule(this::poll, Math.min(interval * millisPerSecond, left), TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) { /* Closed between the check and scheduling. */ }
    }
    private void poll() {
        if (closed.get() || result.isDone()) return;
        if (System.nanoTime() >= deadline) { fail(new ElyOAuth.ApiException("expired_token")); return; }
        try {
            ElyOAuth.Session session = transport.poll(device.deviceCode());
            if (!closed.get()) result.complete(session);
        } catch (ElyOAuth.ApiException ex) {
            switch (ex.code()) {
                case "authorization_pending" -> schedule();
                case "slow_down" -> { interval = Math.min(interval + 5, 120); schedule(); }
                default -> fail(ex);
            }
        } catch (IOException ex) {
            // RFC 8628 recommends reducing polling frequency on transport timeouts.
            interval = Math.min(interval * 2, 120);
            schedule();
        } catch (RuntimeException ex) { fail(ex); }
    }
    private void fail(Exception ex) {
        if (!closed.get()) result.completeExceptionally(ex);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        result.cancel(false);
        worker.shutdownNow();
    }
    @Override public String toString() { return "ElyDeviceLogin{codes=REDACTED}"; }
}
