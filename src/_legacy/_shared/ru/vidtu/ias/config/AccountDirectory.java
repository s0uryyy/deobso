// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.config;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** One-time, read-only copy of IAS storage into deobso's own working directory. */
public final class AccountDirectory {
    public static final String LEGACY = "_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE";
    public static final String DEOBSO = "_DEOBSO_ACCOUNTS_DO_NOT_SEND_TO_ANYONE";
    private AccountDirectory() { }

    public static synchronized Path prepare(Path gameDirectory) throws IOException {
        Path target = gameDirectory.resolve(DEOBSO);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(target);
            return target; // Never merge, recopy or overwrite an existing working copy.
        }
        Path source = gameDirectory.resolve(LEGACY);
        boolean copy = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
        if (copy) requireDirectory(source);
        Files.createDirectories(gameDirectory);
        // Only publish the complete copy. An interrupted copy cannot become active storage.
        Path staging = Files.createTempDirectory(gameDirectory, ".deobso-accounts-copy-");
        try {
            if (copy) {
                Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (!dir.equals(source)) Files.createDirectory(staging.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile()) throw new IOException("Account folder contains a link or special file");
                        Files.copy(file, staging.resolve(source.relativize(file)),
                                LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            // No REPLACE_EXISTING: another process's working directory must not be replaced.
            Files.move(staging, target);
            return target;
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(staging, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Account storage path is not a directory or is a symbolic link");
        }
    }
}
