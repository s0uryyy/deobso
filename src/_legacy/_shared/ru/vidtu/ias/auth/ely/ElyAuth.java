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

    /** No credential-bearing JVM arguments are logged. A JAR merely present is not active. */
    public static boolean injectorAvailable() {
        return injectorStatus() == InjectorStatus.READY;
    }

    public enum InjectorStatus { READY, NOT_ACTIVE, WRONG_ENDPOINT }

    public static InjectorStatus injectorStatus() {
        // ElyPrism can replace Mojang authlib instead of attaching a javaagent.
        // Resolve through the GAME loader, not any unrelated JAR on the system classpath.
        if (replacementAvailable(ElyAuth.class.getClassLoader())) return InjectorStatus.READY;
        boolean active = false;
        ClassLoader[] loaders = {ClassLoader.getSystemClassLoader(),
                ElyAuth.class.getClassLoader(), Thread.currentThread().getContextClassLoader()};
        for (ClassLoader loader : loaders) {
            try {
                Class<?> type = Class.forName("moe.yushi.authlibinjector.AuthlibInjector", false, loader);
                if (activeInjector(type)) { active = true; break; }
            } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
                // Some launchers isolate the agent from the game's class loader.
            }
        }
        if (!active) return InjectorStatus.NOT_ACTIVE;
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .anyMatch(ElyAuth::elyAgentArgument) ? InjectorStatus.READY : InjectorStatus.WRONG_ENDPOINT;
        } catch (SecurityException ex) {
            return InjectorStatus.WRONG_ENDPOINT;
        }
    }

    static boolean replacementAvailable(ClassLoader gameLoader) {
        try {
            Class<?> session = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService", false, gameLoader);
            Class<?> profile = Class.forName("by.ely.authlib.ElyProfileService", false, session.getClassLoader());
            return integratedReplacement(session, profile);
        } catch (ClassNotFoundException | LinkageError | SecurityException ex) {
            return false;
        }
    }

    /** A spare Ely JAR/class is not enough: the resolved session implementation must use it. */
    static boolean integratedReplacement(Class<?> session, Class<?> profile) {
        try {
            java.security.CodeSource sessionSource = session.getProtectionDomain().getCodeSource();
            java.security.CodeSource profileSource = profile.getProtectionDomain().getCodeSource();
            if (sessionSource == null || profileSource == null
                    || !sessionSource.getLocation().equals(profileSource.getLocation())) return false;
            for (java.lang.reflect.Field field : session.getDeclaredFields()) {
                if (field.getType() == profile) return true;
            }
            return false;
        } catch (LinkageError | SecurityException ex) {
            return false;
        }
    }

    static boolean activeInjector(Class<?> type) {
        try {
            return type.getMethod("getClassTransformer").invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            return false;
        }
    }

    static boolean elyAgentArgument(String argument) {
        if (!argument.startsWith("-javaagent:")) return false;
        int equals = argument.indexOf('=');
        if (equals <= "-javaagent:".length()) return false;
        return elyEndpoint(argument.substring(equals + 1));
    }

    static boolean elyEndpoint(String endpoint) {
        try {
            // authlib-injector itself defaults scheme-less addresses to HTTPS.
            URI uri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            int port = uri.getPort();
            if (port != -1 && port != ("https".equalsIgnoreCase(scheme) ? 443 : 80)) return false;
            String host = uri.getHost();
            String path = uri.getRawPath().replaceAll("/+$", "");
            return (("ely.by".equalsIgnoreCase(host) || "authserver.ely.by".equalsIgnoreCase(host)) && path.isEmpty())
                    || ("account.ely.by".equalsIgnoreCase(host) && path.equals("/api/authlib-injector"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
