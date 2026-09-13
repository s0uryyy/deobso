// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.account;

import ru.vidtu.ias.IAS;
import ru.vidtu.ias.auth.LoginData;
import ru.vidtu.ias.auth.ely.ElyAuth;
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

    private ElyAccount(String name, UUID uuid, byte[] encrypted) {
        this.name = name;
        this.uuid = uuid;
        this.encrypted = encrypted.clone();
    }
    public static ElyAccount create(ElyAuth.Session session) throws IOException {
        return new ElyAccount(session.name(), session.uuid(), encrypt(session));
    }
    private static byte[] encrypt(ElyAuth.Session session) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(session.accessToken());
            out.writeUTF(session.clientToken());
            return HardwareCrypt.INSTANCE_V2.encrypt(bytes.toByteArray());
        }
    }
    @Override public String type() { return "deobso:ely_v1"; }
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
                if (!ElyAuth.injectorAvailable()) throw new IOException("Launch with authlib-injector for Ely.by skins and sessions; see docs/ELY.md");
                handler.stage("deobso.ely.refresh");
                ElyAuth.Session session;
                byte[] plain = HardwareCrypt.INSTANCE_V2.decrypt(encrypted);
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                    session = ElyAuth.refresh(in.readUTF(), in.readUTF());
                } finally { Arrays.fill(plain, (byte) 0); }
                if (!uuid.equals(session.uuid())) throw new IOException("Ely.by returned another account UUID");
                encrypted = encrypt(session);
                name = session.name();
                // Refresh rotates tokens: persist even if the login popup was cancelled.
                IAS.saveStorage();
                if (!handler.cancelled()) {
                    handler.success(new LoginData(name, uuid, session.accessToken(), true), false);
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
        String name = in.readUTF();
        UUID uuid = new UUID(in.readLong(), in.readLong());
        int length = in.readInt();
        if (length < 1 || length > 1024 * 1024) throw new IOException("Invalid encrypted Ely account size");
        byte[] encrypted = new byte[length];
        in.readFully(encrypted);
        return new ElyAccount(name, uuid, encrypted);
    }
    @Override public boolean equals(Object other) { return other instanceof ElyAccount account && uuid.equals(account.uuid); }
    @Override public int hashCode() { return uuid.hashCode(); }
    @Override public String toString() { return "ElyAccount{uuid=" + uuid + ", credentials=REDACTED}"; }
}
