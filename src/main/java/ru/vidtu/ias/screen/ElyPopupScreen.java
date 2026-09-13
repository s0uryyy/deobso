// SPDX-License-Identifier: LGPL-3.0-or-later
// deobso account changer additions, 2026.

package ru.vidtu.ias.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.ElyAccount;
import ru.vidtu.ias.auth.ely.ElyAuth;
import java.util.function.Consumer;

/** Ely credentials go only to authserver.ely.by over HTTPS; passwords are never persisted. */
final class ElyPopupScreen extends Screen {
    private final Screen parent;
    private final Consumer<Account> handler;
    private PopupBox username, password, totp;
    private boolean busy, cancelled;
    private String status = "Ely.by — requires authlib-injector (docs/ELY.md)";

    ElyPopupScreen(Screen parent, Consumer<Account> handler) {
        super(Component.literal("Ely.by"));
        this.parent = parent;
        this.handler = handler;
    }
    private PopupBox field(int y, String title, PopupBox previous, boolean secret) {
        PopupBox box = new PopupBox(this.font, this.width / 2 - 140, y, 280, 20,
                previous, Component.literal(title), this::submit, secret);
        box.setMaxLength(secret ? 1024 : 254);
        box.active = !busy;
        if (secret) {
            //? if >=1.21.10 {
            box.addFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward("*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));
            //?} else
            /*box.setFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward("*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));*/
        }
        this.addRenderableWidget(box);
        return box;
    }
    private void label(int y, String text) {
        PopupButton label = new PopupButton(this.width / 2 - 150, y, 300, 20,
                Component.literal(text), b -> {}, java.util.function.Supplier::get);
        label.active = false;
        this.addRenderableWidget(label);
    }
    @Override protected void init() {
        this.clearWidgets();
        int top = Math.max(0, this.height / 2 - 118);
        label(top, status);
        label(top + 22, "E-mail / username");
        username = field(top + 44, "E-mail / username", username, false);
        label(top + 66, "Password (not saved)");
        password = field(top + 88, "Password", password, true);
        label(top + 110, "2FA code (optional)");
        totp = field(top + 132, "2FA code", totp, true);
        PopupButton submit = new PopupButton(this.width / 2 - 140, top + 160, 280, 20,
                Component.literal("Add Ely.by account"), b -> submit(), java.util.function.Supplier::get);
        submit.active = !busy && ElyAuth.injectorAvailable();
        this.addRenderableWidget(submit);
        this.addRenderableWidget(new PopupButton(this.width / 2 - 140, top + 184, 280, 20,
                Component.literal("Cancel"), b -> onClose(), java.util.function.Supplier::get));
    }
    private void submit() {
        if (busy || cancelled || username == null || !ElyAuth.injectorAvailable()) return;
        String user = username.getValue().trim();
        String pass = password.getValue();
        String code = totp.getValue().trim();
        if (user.isEmpty() || pass.isEmpty()) { status = "Enter username and password"; init(); return; }
        if (!code.isEmpty() && !code.matches("[0-9]{6}")) { status = "2FA code must contain six digits"; init(); return; }
        busy = true;
        status = "Signing in to Ely.by...";
        password.setValue("");
        totp.setValue("");
        init();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return ElyAccount.create(ElyAuth.authenticate(user, pass, code)); }
            catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(new java.io.IOException("Ely.by sign-in failed"));
            }
        }, IAS.executor()).whenCompleteAsync((account, error) -> {
            busy = false;
            if (cancelled) return;
            if (error == null) { cancelled = true; handler.accept(account); }
            else { status = "Failed: check network, password and 2FA"; init(); }
        }, this.minecraft);
    }
    @Override public void onClose() {
        cancelled = true;
        if (password != null) password.setValue("");
        if (totp != null) totp.setValue("");
        //$ set_screen 'this.minecraft' 'this.parent'
            this.minecraft.gui.setScreen(this.parent);
    }
}
