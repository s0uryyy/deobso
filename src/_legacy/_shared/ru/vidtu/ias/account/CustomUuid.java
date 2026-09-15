// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.account;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Explicit conversion policy: Minecraft cannot represent a malformed UUID. */
public final class CustomUuid {
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private CustomUuid() { }
    public static boolean valid(String value) {
        return value.isEmpty() || CANONICAL.matcher(value).matches();
    }
    public static UUID resolve(String name, String value, boolean force) {
        if (value.isEmpty()) return OfflineAccount.uuid(name);
        if (valid(value)) return UUID.fromString(value);
        if (!force) throw new IllegalArgumentException("Expected UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        if (value.matches("[0-9a-fA-F]{32}")) {
            return UUID.fromString(value.replaceFirst(
                    "(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        }
        return UUID.nameUUIDFromBytes(("deobso:custom:" + value).getBytes(StandardCharsets.UTF_8));
    }
}
