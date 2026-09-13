// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.auth.ely;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.vidtu.ias.account.CustomUuid;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

/** Ely.by's documented Yggdrasil API. Never logs credentials or server response bodies. */
public final class ElyAuth {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private ElyAuth() { }

    public record Session(String name, UUID uuid, String accessToken, String clientToken) {
        @Override public String toString() { return "ElySession{credentials=REDACTED}"; }
    }

    /** Injector is needed for skin signatures, texture hosts and session/join routing. */
    public static boolean injectorAvailable() {
        try {
            Class.forName("moe.yushi.authlibinjector.AuthlibInjector", false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(arg -> {
            if (!arg.startsWith("-javaagent:")) return false;
            int equals = arg.indexOf('=');
            if (equals < 0) return false;
            String endpoint = arg.substring(equals + 1).replaceAll("/+$", "");
            return endpoint.equals("ely.by") || endpoint.equals("https://authserver.ely.by")
                    || endpoint.equals("https://account.ely.by/api/authlib-injector");
        });
    }

    public static Session authenticate(String username, String password, String totp) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("username", username);
        request.addProperty("password", totp.isEmpty() ? password : password + ":" + totp);
        request.addProperty("clientToken", UUID.randomUUID().toString());
        return parse(post("authenticate", request));
    }

    public static Session refresh(String accessToken, String clientToken) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("clientToken", clientToken);
        return parse(post("refresh", request));
    }

    private static JsonObject post(String action, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://authserver.ely.by/auth/" + action))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() == 401) throw new IOException("Ely.by: check password, token expiry or two-factor code");
            if (response.statusCode() != 200) throw new IOException("Ely.by request failed (HTTP " + response.statusCode() + ")");
            byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Ely.by response too large");
            try { return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException ex) { throw new IOException("Invalid Ely.by response"); }
        }
    }

    private static Session parse(JsonObject response) throws IOException {
        try {
            JsonObject profile = response.getAsJsonObject("selectedProfile");
            String name = profile.get("name").getAsString();
            String id = profile.get("id").getAsString();
            if (!id.matches("[0-9a-fA-F]{32}") && !(id.length() == 36 && CustomUuid.valid(id))) throw new IllegalArgumentException();
            String access = response.get("accessToken").getAsString();
            String client = response.get("clientToken").getAsString();
            if (name.isBlank() || access.isEmpty() || client.isEmpty()) throw new IllegalArgumentException();
            return new Session(name, CustomUuid.resolve(name, id, true), access, client);
        } catch (RuntimeException ex) { throw new IOException("Incomplete Ely.by profile response"); }
    }
}
