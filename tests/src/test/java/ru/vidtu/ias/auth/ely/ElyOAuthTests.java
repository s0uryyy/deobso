package ru.vidtu.ias.auth.ely;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ElyOAuthTests {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ElyOAuth.Session SESSION = new ElyOAuth.Session("Steve", UUID.randomUUID(), "private-access", "private-refresh");

    private static HttpResponse<String> get(String uri) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(uri)).timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void pkceMatchesRfc7636AndAuthorizationHasNoSecret() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ElyOAuth.challenge(verifier));
        URI url = ElyOAuth.authorizationUri(URI.create("http://127.0.0.1:43210/deobso/ely/callback"), "state", verifier);
        Map<String, String> params = ElyOAuthLogin.parseQuery(url.getRawQuery());
        assertEquals("deobso", params.get("client_id"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("code", params.get("response_type"));
        assertEquals(ElyOAuth.SCOPES, params.get("scope"));
        assertFalse(params.containsKey("client_secret"));
        assertFalse(url.toString().contains(verifier));
        assertNotEquals(ElyOAuth.randomSecret(), ElyOAuth.randomSecret());
    }

    @Test void queryDecodingRejectsDuplicatesMalformedAndOversizedInput() {
        assertEquals("a+b=c", ElyOAuthLogin.parseQuery("code=a%2Bb%3Dc").get("code"));
        assertThrows(IllegalArgumentException.class, () -> ElyOAuthLogin.parseQuery("state=x&state=y"));
        assertThrows(IllegalArgumentException.class, () -> ElyOAuthLogin.parseQuery("code=%QZ"));
        assertThrows(IllegalArgumentException.class, () -> ElyOAuthLogin.parseQuery("code"));
        assertThrows(IllegalArgumentException.class, () -> ElyOAuthLogin.parseQuery(null));
        assertThrows(IllegalArgumentException.class, () -> ElyOAuthLogin.parseQuery("x".repeat(17000)));
    }

    @Test void callbackRejectsWrongStateAndPathBeforeExchangingOneCode() throws Exception {
        AtomicInteger exchanged = new AtomicInteger();
        try (ElyOAuthLogin login = new ElyOAuthLogin(Runnable::run, (code, redirect, verifier) -> {
            assertEquals("a+b=c", code);
            assertEquals("127.0.0.1", redirect.getHost());
            assertTrue(verifier.matches("[A-Za-z0-9_-]{43}"));
            exchanged.incrementAndGet();
            return SESSION;
        }, 10000)) {
            Map<String, String> params = ElyOAuthLogin.parseQuery(login.authorizationUri().getRawQuery());
            String redirect = params.get("redirect_uri"), state = params.get("state");
            assertEquals(400, get(redirect + "?code=x&state=wrong").statusCode());
            assertEquals(400, get(redirect + "?code=x&state=" + state + "&state=" + state).statusCode());
            assertEquals(404, get(redirect + "/other?code=x&state=" + state).statusCode());
            assertEquals(0, exchanged.get());
            assertFalse(login.result().isDone());
            HttpResponse<String> response = get(redirect + "?" + ElyOAuth.form(Map.of("state", state, "code", "a+b=c")));
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertFalse(response.body().contains(state));
            assertSame(SESSION, login.result().get(3, TimeUnit.SECONDS));
            assertEquals(1, exchanged.get());
            assertFalse(login.toString().contains(state));
        }
    }

    @Test void denialCancellationAndTimeoutDoNotExchangeTokens() throws Exception {
        ElyOAuthLogin.CodeExchange exchange = (code, redirect, verifier) -> { fail("Must not exchange tokens"); return SESSION; };
        try (ElyOAuthLogin login = new ElyOAuthLogin(Runnable::run, exchange, 10000)) {
            Map<String, String> p = ElyOAuthLogin.parseQuery(login.authorizationUri().getRawQuery());
            assertEquals(200, get(p.get("redirect_uri") + "?state=" + p.get("state") + "&error=access_denied").statusCode());
            assertThrows(ExecutionException.class, () -> login.result().get(3, TimeUnit.SECONDS));
        }
        ElyOAuthLogin cancelled = new ElyOAuthLogin(Runnable::run, exchange, 10000);
        cancelled.close();
        assertTrue(cancelled.result().isCompletedExceptionally());
        cancelled.close(); // Idempotent cleanup.
        try (ElyOAuthLogin timeout = new ElyOAuthLogin(Runnable::run, exchange, 100)) {
            ExecutionException ex = assertThrows(ExecutionException.class, () -> timeout.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, ex.getCause());
        }
    }

    @Test void profileParsingIsStrictAndSessionStringsAreRedacted() throws Exception {
        var profile = JsonParser.parseString("{\"username\":\"Steve\",\"uuid\":\"00112233-4455-6677-8899-aabbccddeeff\"}").getAsJsonObject();
        assertEquals("Steve", ElyOAuth.parseProfile(profile, "access", "refresh").name());
        profile.addProperty("uuid", "1-1-1-1-1");
        assertThrows(java.io.IOException.class, () -> ElyOAuth.parseProfile(profile, "access", "refresh"));
        assertFalse(SESSION.toString().contains(SESSION.accessToken()));
        assertFalse(SESSION.toString().contains(SESSION.refreshToken()));
    }
}
