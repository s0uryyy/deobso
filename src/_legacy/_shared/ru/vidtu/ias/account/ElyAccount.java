// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.account;

import ru.vidtu.ias.IAS;
import ru.vidtu.ias.auth.LoginData;
import ru.vidtu.ias.auth.ely.ElyAuth;
import ru.vidtu.ias.auth.ely.ElyOAuth;
import ru.vidtu.ias.auth.handlers.LoginHandler;
import ru.vidtu.ias.crypt.HardwareCrypt;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** No password is saved. Tokens use IAS hardware-bound encryption v2. */
public final class ElyAccount implements Account {
    private String name;
    private final UUID uuid;
    private byte[] encrypted;
    private final boolean oauth;

    private ElyAccount(String name, UUID uuid, byte[] encrypted, boolean oauth) {
        this.oauth = oauth;
        this.name = name;
        this.uuid = uuid;
        this.encrypted = encrypted.clone();
    }
    public static ElyAccount create(ElyAuth.Session session) throws IOException {
        return new ElyAccount(session.name(), session.uuid(), encrypt(session.accessToken(), session.clientToken()), false);
    }
    public static ElyAccount create(ElyOAuth.Session session) throws IOException {
        return new ElyAccount(session.name(), session.uuid(), encrypt(session.accessToken(), session.refreshToken()), true);
    }
    private static byte[] encrypt(String accessToken, String renewalToken) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(accessToken);
            out.writeUTF(renewalToken);
            return HardwareCrypt.INSTANCE_V2.encrypt(bytes.toByteArray());
        }
    }
    @Override public String type() { return oauth ? "deobso:ely_oauth_v1" : "deobso:ely_v1"; }
    @Override public String typeTipKey() { return "deobso.account.ely"; }
    @Override public UUID uuid() { return uuid; }
    @Override public String name() { return name; }
    @Override public boolean canLogin() { return true; }
    @Override public boolean insecure() { return false; }
    @Override public UUID skin() { return uuid; }

    @Override public void login(LoginHandler handler, Runnable onComplete) {
        CompletableFuture.runAsync(() -> {
            try {
                if (handler.cancelled()) return;
                ElyAuth.InjectorStatus injector = ElyAuth.injectorStatus();
                if (injector != ElyAuth.InjectorStatus.READY) {
                    handler.error(new ru.vidtu.ias.utils.exceptions.FriendlyException(
                            "Ely.by auth backend check: " + injector + ". See docs/ELY.md.",
                            injector == ElyAuth.InjectorStatus.NOT_ACTIVE
                                    ? "deobso.ely.injector.inactive" : "deobso.ely.injector.endpoint"));
                    return;
                }
                handler.stage("deobso.ely.refresh");
                String updatedName;
                UUID updatedUuid;
                String access;
                String renewal;
                byte[] plain = HardwareCrypt.INSTANCE_V2.decrypt(encrypted);
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                    String oldAccess = in.readUTF();
                    String oldRenewal = in.readUTF();
                    if (oauth) {
                        ElyOAuth.Session session = ElyOAuth.refresh(oldRenewal);
                        updatedName = session.name(); updatedUuid = session.uuid();
                        access = session.accessToken(); renewal = session.refreshToken();
                    } else {
                        ElyAuth.Session session = ElyAuth.refresh(oldAccess, oldRenewal);
                        updatedName = session.name(); updatedUuid = session.uuid();
                        access = session.accessToken(); renewal = session.clientToken();
                    }
                } finally { Arrays.fill(plain, (byte) 0); }
                if (!uuid.equals(updatedUuid)) throw new IOException("Ely.by returned another account UUID");
                encrypted = encrypt(access, renewal);
                name = updatedName;
                // Refresh rotates tokens: persist even if the login popup was cancelled.
                IAS.saveStorage();
                if (!handler.cancelled()) {
                    handler.success(new LoginData(name, uuid, access, true), false);
                    if (onComplete != null) onComplete.run();
                }
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!handler.cancelled()) handler.error(new IOException("Ely.by login failed. Check connection, credentials, 2FA or re-add the account."));
            }
        }, IAS.executor());
    }
    @Override public void write(DataOutput out) throws IOException {
        out.writeUTF(name);
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
        out.writeInt(encrypted.length);
        out.write(encrypted);
    }
    public static ElyAccount read(DataInput in) throws IOException {
        return read(in, false);
    }
    public static ElyAccount readOAuth(DataInput in) throws IOException {
        return read(in, true);
    }
    private static ElyAccount read(DataInput in, boolean oauth) throws IOException {
        String name = in.readUTF();
        UUID uuid = new UUID(in.readLong(), in.readLong());
        int length = in.readInt();
        if (length < 1 || length > 1024 * 1024) throw new IOException("Invalid encrypted Ely account size");
        byte[] encrypted = new byte[length];
        in.readFully(encrypted);
        return new ElyAccount(name, uuid, encrypted, oauth);
    }
    @Override public boolean equals(Object other) { return other instanceof ElyAccount account && uuid.equals(account.uuid); }
    @Override public int hashCode() { return uuid.hashCode(); }
    @Override public String toString() { return "ElyAccount{uuid=" + uuid + ", credentials=REDACTED}"; }
}
