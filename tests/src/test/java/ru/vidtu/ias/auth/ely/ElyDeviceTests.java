package ru.vidtu.ias.auth.ely;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ElyDeviceTests {
    private static final ElyOAuth.Device DEVICE = new ElyOAuth.Device("private-device-code", "ABCD-EFGH",
            URI.create("https://account.ely.by/code"), 1, 100);
    private static final ElyOAuth.Session SESSION = new ElyOAuth.Session("Steve", UUID.randomUUID(), "access", "refresh");

    @Test void pendingAndSlowDownAreNotTerminalErrors() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        try (ElyDeviceLogin login = new ElyDeviceLogin(new ElyDeviceLogin.Transport() {
            public ElyOAuth.Device begin() { return DEVICE; }
            public ElyOAuth.Session poll(String code) throws java.io.IOException {
                assertEquals(DEVICE.deviceCode(), code);
                int count = polls.incrementAndGet();
                if (count == 1) throw new ElyOAuth.ApiException("authorization_pending");
                if (count == 2) throw new ElyOAuth.ApiException("slow_down");
                return SESSION;
            }
        }, device -> ready.incrementAndGet(), 10)) {
            assertSame(SESSION, login.result().get(3, TimeUnit.SECONDS));
            assertEquals(1, ready.get());
            assertEquals(3, polls.get());
        }
    }
    @Test void denialAndExpiryStopPolling() throws Exception {
        for (String error : new String[]{"access_denied", "expired_token", "invalid_client"}) {
            try (ElyDeviceLogin login = new ElyDeviceLogin(new ElyDeviceLogin.Transport() {
                public ElyOAuth.Device begin() { return DEVICE; }
                public ElyOAuth.Session poll(String code) throws java.io.IOException { throw new ElyOAuth.ApiException(error); }
            }, device -> {}, 10)) {
                ExecutionException ex = assertThrows(ExecutionException.class, () -> login.result().get(3, TimeUnit.SECONDS));
                assertEquals(error, ((ElyOAuth.ApiException) ex.getCause()).code());
            }
        }
    }
    @Test void localDeadlineAndCancelAreBounded() throws Exception {
        ElyDeviceLogin.Transport pending = new ElyDeviceLogin.Transport() {
            public ElyOAuth.Device begin() {
                return new ElyOAuth.Device(DEVICE.deviceCode(), DEVICE.userCode(), DEVICE.verificationUri(), 1, 2);
            }
            public ElyOAuth.Session poll(String code) throws java.io.IOException { throw new ElyOAuth.ApiException("authorization_pending"); }
        };
        try (ElyDeviceLogin login = new ElyDeviceLogin(pending, device -> {}, 10)) {
            ExecutionException ex = assertThrows(ExecutionException.class, () -> login.result().get(3, TimeUnit.SECONDS));
            assertEquals("expired_token", ((ElyOAuth.ApiException) ex.getCause()).code());
        }
        ElyDeviceLogin cancelled = new ElyDeviceLogin(pending, device -> {}, 1000);
        cancelled.close(); cancelled.close();
        assertTrue(cancelled.result().isCancelled());
    }
    @Test void verificationUrlCannotSendUserToAnotherHost() throws Exception {
        var json = JsonParser.parseString("{\"device_code\":\"private-device-code\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://account.ely.by/code\",\"expires_in\":600,\"interval\":5}").getAsJsonObject();
        ElyOAuth.Device device = ElyOAuth.parseDevice(json);
        assertEquals("https://account.ely.by/code?user_code=ABCD-EFGH", device.browserUri().toString());
        assertFalse(device.toString().contains(device.deviceCode()));
        for (String url : new String[]{"http://account.ely.by/code", "https://example.com/code", "https://account.ely.by/code?redirect=evil", "https://evil@account.ely.by/code", "https://account.ely.by:444/code"}) {
            json.addProperty("verification_uri", url);
            assertThrows(java.io.IOException.class, () -> ElyOAuth.parseDevice(json));
        }
        assertEquals("request_failed", new ElyOAuth.ApiException("secret-server-response").code());
    }
}
