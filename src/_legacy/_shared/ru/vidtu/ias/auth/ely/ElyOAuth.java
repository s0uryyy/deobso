// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.auth.ely;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.vidtu.ias.account.CustomUuid;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Public desktop client: Authorization Code + S256 PKCE, never a client secret. */
public final class ElyOAuth {
    public static final String CLIENT_ID = "deobso";
    public static final String SCOPES = "account_info minecraft_server_session offline_access";
    private static final String TOKEN_URL = "https://account.ely.by/api/oauth2/v1/token";
    private static final String PROFILE_URL = "https://account.ely.by/api/account/v1/info";
    private ElyOAuth() { }

    public record Session(String name, UUID uuid, String accessToken, String refreshToken) {
        @Override public String toString() { return "ElyOAuthSession{credentials=REDACTED}"; }
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    public static URI authorizationUri(URI redirect, String state, String verifier) {
        return URI.create("https://account.ely.by/oauth2/v1?" + form(Map.of(
                "client_id", CLIENT_ID, "redirect_uri", redirect.toString(), "response_type", "code",
                "scope", SCOPES, "state", state, "code_challenge", challenge(verifier),
                "code_challenge_method", "S256", "prompt", "select_account")));
    }

    public static Session exchange(String code, URI redirect, String verifier) throws IOException {
        JsonObject tokens = request(TOKEN_URL, form(Map.of("grant_type", "authorization_code",
                "client_id", CLIENT_ID, "code", code, "redirect_uri", redirect.toString(),
                "code_verifier", verifier)), null);
        return session(tokens, null);
    }

    public static Session refresh(String token) throws IOException {
        JsonObject tokens = request(TOKEN_URL, form(Map.of("grant_type", "refresh_token",
                "client_id", CLIENT_ID, "refresh_token", token, "scope", SCOPES)), null);
        return session(tokens, token);
    }

    private static Session session(JsonObject tokens, String previousRefresh) throws IOException {
        try {
            String access = tokens.get("access_token").getAsString();
            String refresh = tokens.has("refresh_token") ? tokens.get("refresh_token").getAsString() : previousRefresh;
            if (access.isBlank() || refresh == null || refresh.isBlank()
                    || !"Bearer".equalsIgnoreCase(tokens.get("token_type").getAsString())) {
                throw new IllegalArgumentException();
            }
            JsonObject profile = request(PROFILE_URL, null, access);
            return parseProfile(profile, access, refresh);
        } catch (RuntimeException ex) { throw new IOException("Invalid Ely.by OAuth response"); }
    }

    static Session parseProfile(JsonObject profile, String access, String refresh) throws IOException {
        try {
            String name = profile.get("username").getAsString();
            String uuid = profile.get("uuid").getAsString();
            if (name.isBlank() || uuid.length() != 36 || !CustomUuid.valid(uuid)) throw new IllegalArgumentException();
            return new Session(name, UUID.fromString(uuid), access, refresh);
        } catch (RuntimeException ex) { throw new IOException("Invalid Ely.by profile"); }
    }

    public static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static JsonObject request(String endpoint, String body, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Ely.by OAuth request failed (HTTP " + status + ")");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Ely.by response too large");
                try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch (RuntimeException ex) { throw new IOException("Invalid Ely.by JSON response"); }
            }
        } finally { connection.disconnect(); }
    }
}
