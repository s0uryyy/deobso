package deobso;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.vidtu.ias.account.*;
import ru.vidtu.ias.config.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class AccountDirectoryTests {
    @TempDir Path game;
    @BeforeEach void clear() { IASStorage.ACCOUNTS.clear(); }
    @AfterEach void cleanup() { IASStorage.ACCOUNTS.clear(); }

    private Path source() throws IOException {
        Path source = game.resolve(AccountDirectory.LEGACY);
        Files.createDirectories(source.resolve(".hidden/nested"));
        Files.writeString(source.resolve(".hidden/nested/extra"), "preserve me");
        Files.writeString(source.resolve("READ_ME_IMPORTANT.txt"), "original notice");
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(
                Files.newOutputStream(source.resolve(".hidden/accounts_v1.do_not_send_to_anyone"))))) {
            out.writeShort(1);
            Account.writeTyped(out, new OfflineAccount("Steve", null));
        }
        return source;
    }

    @Test void copiesEntireFolderThenSavesOnlyToCopy() throws IOException {
        Path source = source();
        Path originalFile = source.resolve(".hidden/accounts_v1.do_not_send_to_anyone");
        byte[] original = Files.readAllBytes(originalFile);
        var modified = Files.getLastModifiedTime(originalFile);
        IASStorage.disclaimers(game); // Startup writes notices before loading accounts.
        IASStorage.load(game);
        assertEquals(List.of(new OfflineAccount("Steve", null)), IASStorage.ACCOUNTS);
        Path target = game.resolve(AccountDirectory.DEOBSO);
        assertEquals("preserve me", Files.readString(target.resolve(".hidden/nested/extra")));
        assertEquals("original notice", Files.readString(source.resolve("READ_ME_IMPORTANT.txt")));
        IASStorage.ACCOUNTS.add(new OfflineAccount("Alex", UUID.randomUUID(), null));
        IASStorage.save(game);
        IASStorage.gameDisclaimerShown(game);
        assertArrayEquals(original, Files.readAllBytes(originalFile));
        assertEquals(modified, Files.getLastModifiedTime(originalFile));
        assertFalse(Files.exists(source.resolve(".hidden/game_disclaimer_shown")));
        assertEquals(2, FolderAccounts.read(target).size());
        assertEquals(1, FolderAccounts.read(source).size());
    }

    @Test void subsequentLaunchNeverOverwritesOrMergesWorkingCopy() throws IOException {
        Path source = source();
        Path target = AccountDirectory.prepare(game);
        Files.writeString(target.resolve(".hidden/nested/extra"), "deobso changes");
        Files.writeString(source.resolve("new-original-file"), "must not be merged");
        assertEquals(target, AccountDirectory.prepare(game));
        assertEquals("deobso changes", Files.readString(target.resolve(".hidden/nested/extra")));
        assertFalse(Files.exists(target.resolve("new-original-file")));
    }

    @Test void freshInstallCreatesOnlyDeobsoStorage() throws IOException {
        IASStorage.load(game);
        assertFalse(Files.exists(game.resolve(AccountDirectory.LEGACY)));
        assertTrue(Files.isRegularFile(game.resolve(AccountDirectory.DEOBSO)
                .resolve(".hidden/accounts_v1.do_not_send_to_anyone")));
    }

    @Test void existingEmptyWorkingFolderIsRespected() throws IOException {
        source();
        Path target = Files.createDirectory(game.resolve(AccountDirectory.DEOBSO));
        assertEquals(target, AccountDirectory.prepare(game));
        try (var contents = Files.list(target)) { assertEquals(0, contents.count()); }
    }

    @Test void invalidSourceDoesNotPublishCopyOrWriteOriginal() throws IOException {
        Path original = game.resolve(AccountDirectory.LEGACY);
        Files.writeString(original, "not a directory");
        assertThrows(IOException.class, () -> AccountDirectory.prepare(game));
        assertThrows(RuntimeException.class, () -> IASStorage.save(game));
        assertFalse(Files.exists(game.resolve(AccountDirectory.DEOBSO)));
        assertEquals("not a directory", Files.readString(original));
    }

    @Test void linkedSourceEntryFailsWithoutPublishingPartialCopy() throws IOException {
        Path source = source();
        try {
            Files.createSymbolicLink(source.resolve("linked"), source.resolve("READ_ME_IMPORTANT.txt"));
        } catch (UnsupportedOperationException | FileSystemException ex) {
            Assumptions.abort("Symbolic links unavailable on this platform");
        }
        assertThrows(IOException.class, () -> AccountDirectory.prepare(game));
        assertFalse(Files.exists(game.resolve(AccountDirectory.DEOBSO)));
        assertTrue(Files.exists(source.resolve(".hidden/accounts_v1.do_not_send_to_anyone")));
        try (var entries = Files.list(game)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".deobso-accounts-copy-")));
        }
    }
}
