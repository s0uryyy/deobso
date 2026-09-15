// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.config;

import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Full file-browser dialog with an Open button, not TinyFD's Windows tree picker. */
public final class DesktopFolderPicker {
    private DesktopFolderPicker() { }

    public static CompletableFuture<Path> open(Path initial, String title, String approve) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        AtomicReference<JDialog> window = new AtomicReference<>();
        result.whenComplete((path, error) -> {
            if (result.isCancelled()) SwingUtilities.invokeLater(() -> {
                JDialog dialog = window.get();
                if (dialog != null) dialog.dispose();
            });
        });
        SwingUtilities.invokeLater(() -> {
            if (result.isDone()) return;
            String previous = UIManager.getLookAndFeel().getClass().getName();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                JFileChooser chooser = new JFileChooser(initial.toFile()) {
                    @Override protected JDialog createDialog(Component parent) {
                        JDialog dialog = super.createDialog(parent);
                        dialog.setAlwaysOnTop(true);
                        window.set(dialog);
                        return dialog;
                    }
                };
                chooser.setDialogTitle(title);
                chooser.setApproveButtonText(approve);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setMultiSelectionEnabled(false);
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileHidingEnabled(false);
                if (result.isDone()) return;
                int choice = chooser.showOpenDialog(null);
                if (choice != JFileChooser.APPROVE_OPTION) { result.complete(null); return; }
                Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                if (!Files.isDirectory(selected)) throw new IllegalArgumentException("Select a folder");
                result.complete(selected);
            } catch (Exception | LinkageError ex) {
                result.completeExceptionally(new IllegalStateException("Desktop folder picker is unavailable"));
            } finally {
                JDialog dialog = window.getAndSet(null);
                if (dialog != null) dialog.dispose();
                try { UIManager.setLookAndFeel(previous); } catch (Exception ignored) { }
            }
        });
        return result;
    }
}
