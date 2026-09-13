package deobso;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.vidtu.ias.account.*;
import ru.vidtu.ias.auth.ely.ElyAuth;
import ru.vidtu.ias.config.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class AccountTests {
    @TempDir Path temporary;
    @BeforeEach void clear() { IASStorage.ACCOUNTS.clear(); }
    @AfterEach void cleanup() { IASStorage.ACCOUNTS.clear(); }

    static Account roundTrip(Account account) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { Account.writeTyped(out, account); }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Account result = Account.readTyped(in);
            assertEquals(-1, in.read());
            return result;
        }
    }

    @Test void canonicalAndForcedUuidPolicy() {
        String canonical = "00112233-4455-6677-8899-aabbccddeeff";
        assertTrue(CustomUuid.valid(canonical.toUpperCase(Locale.ROOT)));
        assertFalse(CustomUuid.valid("1-1-1-1-1")); // UUID.fromString alone is too permissive.
        assertFalse(CustomUuid.valid(" " + canonical));
        assertFalse(CustomUuid.valid(canonical.replace("-", "")));
        assertThrows(IllegalArgumentException.class, () -> CustomUuid.resolve("Steve", "invalid", false));
        assertEquals(UUID.fromString(canonical), CustomUuid.resolve("Steve", canonical.replace("-", ""), true));
        assertEquals(CustomUuid.resolve("Steve", "invalid", true), CustomUuid.resolve("Alex", "invalid", true));
        assertEquals(OfflineAccount.uuid("Steve"), CustomUuid.resolve("Steve", "", false));
    }

    @Test void offlineFormatsAndDistinctCustomIdentities() throws IOException {
        UUID custom = UUID.randomUUID();
        UUID skin = UUID.randomUUID();
        OfflineAccount vanilla = new OfflineAccount("Steve", skin);
        OfflineAccount changed = new OfflineAccount("Steve", custom, skin);
        assertEquals("ias:offline_v2", vanilla.type());
        assertEquals("deobso:offline_v3", changed.type());
        assertNotEquals(vanilla, changed);
        for (Account account : List.of(vanilla, changed, new OfflineAccount("Alex", custom, null))) {
            Account restored = roundTrip(account);
            assertEquals(account, restored);
            assertEquals(account.uuid(), restored.uuid());
            assertEquals(account.skin(), restored.skin());
        }
    }

    @Test void legacyV1StillLoads() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("ias:offline_v1"); out.writeUTF("Steve");
        }
        Account account = Account.readTyped(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("Steve", account.name());
        assertEquals(OfflineAccount.uuid("Steve"), account.uuid());
    }

    @Test void readsAllThreeFolderLevelsWithoutChangingSourceOrLiveAccounts() throws IOException {
        Account account = new OfflineAccount("Steve", UUID.randomUUID(), null);
        IASStorage.ACCOUNTS.add(account);
        IASStorage.save(temporary);
        Path folder = temporary.resolve(AccountDirectory.DEOBSO);
        Path hidden = folder.resolve(".hidden");
        Path file = hidden.resolve("accounts_v1.do_not_send_to_anyone");
        byte[] before = Files.readAllBytes(file);
        var time = Files.getLastModifiedTime(file);
        Account live = new OfflineAccount("Alex", null);
        IASStorage.ACCOUNTS.clear(); IASStorage.ACCOUNTS.add(live);
        for (Path selected : List.of(temporary, folder, hidden)) {
            assertEquals(List.of(account), FolderAccounts.read(selected));
            assertEquals(List.of(live), IASStorage.ACCOUNTS);
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(time, Files.getLastModifiedTime(file));
        }
    }

    @Test void importingNeverCreatesStorageInEmptyFolder() throws IOException {
        assertThrows(IOException.class, () -> FolderAccounts.read(temporary));
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void selectedOnlyDeduplicationAndRollback() {
        Account a = new OfflineAccount("Steve", null), b = new OfflineAccount("Alex", null);
        IASStorage.ACCOUNTS.add(a);
        assertEquals(0, FolderAccounts.merge(List.of(a), () -> fail("Duplicate-only import must not write")));
        assertEquals(List.of(a), IASStorage.ACCOUNTS);
        assertThrows(IllegalStateException.class, () -> FolderAccounts.merge(List.of(b), () -> { throw new IllegalStateException("disk full"); }));
        assertEquals(List.of(a), IASStorage.ACCOUNTS);
        assertEquals(1, FolderAccounts.merge(List.of(a, b, b), () -> {}));
        assertEquals(List.of(a, b), IASStorage.ACCOUNTS);
    }

    @Test void corruptAndCompressedBombRejected() throws IOException {
        Path file = temporary.resolve("accounts_v1.do_not_send_to_anyone");
        Files.write(file, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> FolderAccounts.read(temporary));
        try (var out = new DeflaterOutputStream(Files.newOutputStream(file))) {
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 17; i++) out.write(zeros);
        }
        assertThrows(IOException.class, () -> FolderAccounts.read(temporary));
        assertTrue(IASStorage.ACCOUNTS.isEmpty());
    }

    @Test void elySerializationDoesNotContainPlainTokens() throws IOException {
        ElyAuth.Session session = new ElyAuth.Session("Steve", UUID.randomUUID(), "private-access-token", "private-client-token");
        ElyAccount account = ElyAccount.create(session);
        assertFalse(session.toString().contains(session.accessToken()));
        assertEquals(account, roundTrip(account));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        account.write(new DataOutputStream(bytes));
        String raw = bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(session.accessToken()));
        assertFalse(raw.contains(session.clientToken()));
    }
}
