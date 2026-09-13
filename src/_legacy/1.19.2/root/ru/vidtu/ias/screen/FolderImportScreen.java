// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.config.FolderAccounts;
import java.nio.file.*;
import java.util.*;

/** In-game folder picker followed by explicit per-account selection. */
final class FolderImportScreen extends Screen {
    private final Screen parent;
    private Path folder;
    private List<Path> directories = List.of();
    private List<Account> accounts;
    private final Set<Account> selected = new LinkedHashSet<>();
    private int page;
    private PopupBox folderPath;
    private boolean closed;
    private int rows;
    private boolean busy;
    private String status = "Choose folder; source is read-only";

    FolderImportScreen(Screen parent) {
        super(Component.literal("Add accounts from folder"));
        this.parent = parent;
    }

    private void button(int x, int y, int width, String text, Runnable action, boolean enabled) {
        PopupButton button = new PopupButton(x, y, width, 20, Component.literal(text), b -> action.run(), ru.vidtu.ias.legacy.LegacyTooltip.EMPTY);
        button.active = enabled;
        this.addRenderableWidget(button);
    }

    @Override
    protected void init() {
        this.clearWidgets();
        boolean first = folder == null;
        if (folder == null) folder = this.minecraft.gameDirectory.toPath().toAbsolutePath();
        int left = Math.max(4, this.width / 2 - 180);
        int wide = Math.min(360, this.width - 8);
        rows = Math.max(1, (this.height - 150) / 22);
        button(left, 4, wide, status, () -> {}, false);
        if (accounts == null) {
            folderPath = new PopupBox(this.font, left, 26, wide - 46, 20, null,
                    Component.literal("Folder path"), this::openPath, false, Component.literal("Folder path"));
            folderPath.setMaxLength(4096);
            folderPath.setValue(folder.toString());
            folderPath.active = !busy;
            this.addRenderableWidget(folderPath);
            button(left + wide - 42, 26, 42, "Go", this::openPath, !busy);
        } else {
            button(left, 26, wide, "Select accounts; encrypted accounts stay encrypted", () -> {}, false);
        }
        if (accounts == null) {
            button(left, 48, wide / 2 - 2, "Parent folder", () -> browse(folder.getParent()), !busy && folder.getParent() != null);
            button(left + wide / 2, 48, wide / 2, "Use this folder", this::read, !busy);
        } else {
            button(left, 48, wide / 2 - 2, "Select all", () -> { selected.addAll(accounts); init(); }, !busy);
            button(left + wide / 2, 48, wide / 2, "Clear selection", () -> { selected.clear(); init(); }, !busy);
        }
        int size = accounts == null ? directories.size() : accounts.size();
        page = Math.max(0, Math.min(page, Math.max(0, (size - 1) / rows)));
        for (int i = page * rows; i < Math.min(size, (page + 1) * rows); i++) {
            int y = 72 + (i - page * rows) * 22;
            if (accounts == null) {
                Path path = directories.get(i);
                button(left, y, wide, path.getFileName().toString() + "/", () -> browse(path), !busy);
            } else {
                Account account = accounts.get(i);
                button(left, y, wide, (selected.contains(account) ? "[x] " : "[ ] ") + account.name() + " | " + account.uuid(),
                        () -> { if (!selected.remove(account)) selected.add(account); init(); }, !busy);
            }
        }
        button(left, this.height - 70, wide / 2 - 2, "Previous page", () -> { page--; init(); }, !busy && page > 0);
        button(left + wide / 2, this.height - 70, wide / 2, "Next page", () -> { page++; init(); }, !busy && (page + 1) * rows < size);
        button(left, this.height - 46, wide, accounts == null ? "Browse / refresh folders" : "Import selected (" + selected.size() + ")",
                accounts == null ? () -> browse(folder) : this::merge, !busy && (accounts == null || !selected.isEmpty()));
        button(left, this.height - 22, wide, "Back / Cancel", () -> {
            if (accounts != null && !busy) { accounts = null; selected.clear(); page = 0; browse(folder); }
            else onClose();
        }, true);
        if (first) browse(folder);
    }

    private void openPath() {
        if (busy) return;
        try { browse(Path.of(folderPath.getValue()).toAbsolutePath().normalize()); }
        catch (RuntimeException ex) { status = "Invalid folder path"; init(); }
    }

    private void browse(Path path) {
        if (path == null || busy) return;
        busy = true;
        status = "Reading folder...";
        init();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var stream = Files.list(path)) {
                return stream.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .limit(10000).toList();
            } catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
        }, IAS.executor()).whenCompleteAsync((result, error) -> {
            busy = false;
            if (closed) return;
            if (error == null) { folder = path; directories = result; page = 0; status = "Choose folder; source is read-only"; }
            else status = "Cannot read folder (permissions or missing directory)";
            init();
        }, this.minecraft);
    }

    private void read() {
        busy = true;
        status = "Reading accounts...";
        init();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return FolderAccounts.read(folder); }
            catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
        }, IAS.executor()).whenCompleteAsync((result, error) -> {
            busy = false;
            if (closed) return;
            if (error == null) { accounts = result; selected.clear(); page = 0; status = "Found " + result.size() + " accounts; select before importing"; }
            else status = "Cannot import: missing, damaged or unsupported IAS storage";
            init();
        }, this.minecraft);
    }

    private void merge() {
        try {
            int count = FolderAccounts.merge(selected, IAS::saveStorage);
            selected.clear();
            status = "Imported " + count + " accounts; duplicates skipped";
        } catch (RuntimeException ex) { status = "Save failed; selection preserved. Check destination permissions."; }
        init();
    }

    @Override
    public void onClose() {
        closed = true;
        this.minecraft.setScreen(this.parent);
    }
}
