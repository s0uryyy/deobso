// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.config;

import ru.vidtu.ias.account.Account;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Read-only importer. Never calls IASStorage.load, migrators or writes to the source. */
public final class FolderAccounts {
    private static final String FILE = "accounts_v1.do_not_send_to_anyone";
    private static final int LIMIT = 16 * 1024 * 1024;
    private FolderAccounts() { }

    public static List<Account> read(Path folder) throws IOException {
        Path file = null;
        for (String relative : List.of(FILE, ".hidden/" + FILE,
                AccountDirectory.DEOBSO + "/.hidden/" + FILE,
                AccountDirectory.LEGACY + "/.hidden/" + FILE)) {
            Path candidate = folder.resolve(relative);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                file = candidate;
                break;
            }
        }
        if (file == null) throw new IOException("No IAS account storage in selected folder");
        if (Files.size(file) > LIMIT) throw new IOException("Account storage is too large");
        // Limit inflated data too: an imported file is untrusted input.
        byte[] bytes;
        try (InputStream raw = Files.newInputStream(file);
             InputStream zip = new InflaterInputStream(raw)) {
            bytes = zip.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT) throw new IOException("Inflated account storage is too large");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = in.readUnsignedShort();
            List<Account> accounts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) accounts.add(Account.readTyped(in));
            if (in.read() != -1) throw new IOException("Unexpected trailing account data");
            return List.copyOf(new LinkedHashSet<>(accounts));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Unsupported account format", ex);
        }
    }

    /** Caller owns the Minecraft thread; failure restores the live list. */
    public static int merge(Collection<Account> selected, Runnable save) {
        List<Account> before = new ArrayList<>(IASStorage.ACCOUNTS);
        try {
            for (Account account : selected) {
                if (!IASStorage.ACCOUNTS.contains(account)) IASStorage.ACCOUNTS.add(account);
            }
            if (IASStorage.ACCOUNTS.size() > 65535) throw new IllegalStateException("Too many accounts");
            int added = IASStorage.ACCOUNTS.size() - before.size();
            if (added > 0) save.run();
            return added;
        } catch (RuntimeException ex) {
            IASStorage.ACCOUNTS.clear();
            IASStorage.ACCOUNTS.addAll(before);
            throw ex;
        }
    }
}
