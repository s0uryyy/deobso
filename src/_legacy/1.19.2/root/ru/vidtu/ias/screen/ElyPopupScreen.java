// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.ElyAccount;
import ru.vidtu.ias.auth.ely.ElyOAuthLogin;
import java.util.function.Consumer;

/** Browser-only Ely.by authentication. No password or 2FA fields in Minecraft. */
final class ElyPopupScreen extends Screen {
    private final Screen parent;
    private final Consumer<Account> handler;
    private ElyOAuthLogin login;
    private boolean started, busy, closed;
    private String status = "Ely.by: opening browser...";

    ElyPopupScreen(Screen parent, Consumer<Account> handler) {
        super(Component.literal("Ely.by"));
        this.parent = parent;
        this.handler = handler;
    }
    private void button(int y, String text, Runnable action, boolean active) {
        int width = Math.min(360, this.width - 8);
        PopupButton button = new PopupButton((this.width - width) / 2, y, width, 20,
                Component.literal(text), b -> action.run(), ru.vidtu.ias.legacy.LegacyTooltip.EMPTY);
        button.active = active;
        this.addRenderableWidget(button);
    }
    @Override protected void init() {
        this.clearWidgets();
        int top = Math.max(4, this.height / 2 - 66);
        button(top, status, () -> {}, false);
        button(top + 24, "Sign in on Ely.by, then return to Minecraft", () -> {}, false);
        button(top + 48, "Open browser", this::openBrowser, busy && login != null);
        button(top + 72, "Try again", this::start, !busy);
        button(top + 96, "Cancel", this::onClose, true);
    }
    @Override public void tick() {
        super.tick();
        if (!started && !closed) { started = true; start(); }
    }
    private void start() {
        if (closed || busy) return;
        if (login != null) login.close();
        try {
            login = new ElyOAuthLogin(IAS.executor());
            ElyOAuthLogin attempt = login;
            busy = true;
            status = "Waiting for authorization in browser...";
            init();
            attempt.result().thenApplyAsync(session -> {
                try { return ElyAccount.create(session); }
                catch (java.io.IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
            }, IAS.executor()).whenCompleteAsync((account, error) -> {
                if (closed || login != attempt) return;
                busy = false;
                attempt.close();
                if (error == null) { closed = true; handler.accept(account); }
                else { status = "Login failed, declined or timed out. Try again."; init(); }
            }, this.minecraft);
            openBrowser();
        } catch (java.io.IOException | RuntimeException ex) {
            busy = false;
            if (login != null) login.close();
            login = null;
            status = "Cannot start local callback. Check firewall and retry.";
            init();
        }
    }
    private void openBrowser() {
        if (closed || login == null || !busy) return;
        try { net.minecraft.Util.getPlatform().openUri(login.authorizationUri().toString()); }
        catch (RuntimeException ex) {
            status = "Browser could not open. Use Open browser to retry.";
            init();
        }
    }
    @Override public void removed() {
        closed = true;
        if (login != null) login.close();
        super.removed();
    }
    @Override public void onClose() {
        closed = true;
        if (login != null) login.close();
        this.minecraft.setScreen(this.parent);
    }
}
