// SPDX-License-Identifier: LGPL-3.0-or-later
// Rendering/layout adapted from IAS MicrosoftPopupScreen (see upstream NOTICE).
package ru.vidtu.ias.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.network.chat.Component;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.ElyAccount;
import ru.vidtu.ias.auth.ely.ElyDeviceLogin;
import ru.vidtu.ias.auth.ely.ElyOAuth;
import java.util.function.Consumer;
import com.mojang.blaze3d.vertex.PoseStack;

/** Same modal frame, scaled title, stage label and Back button as Microsoft login. */
final class ElyPopupScreen extends Screen {
    private final Screen parent;
    private final Consumer<Account> handler;
    private ElyDeviceLogin login;
    private ElyOAuth.Device device;
    private boolean started, busy, closed;
    private int attemptId;
    private Component stage = text("deobso.ely.initializing").withStyle(ChatFormatting.YELLOW);
    private MultiLineLabel label;

    ElyPopupScreen(Screen parent, Consumer<Account> handler) {
        super(text("deobso.ely.title"));
        this.parent = parent;
        this.handler = handler;
    }
    private static Component text(String key, Object... args) { return Component.translatable(key, args); }
    private void stage(String key, ChatFormatting color, Object... args) {
        stage = text(key, args).copy().withStyle(color);
        label = null;
    }
    @Override protected void init() {
        this.clearWidgets();
        this.label = null;
        // Init parent.
        if (this.parent != null) {
            this.parent.init(this.minecraft, this.width, this.height);
        }


        PopupButton browser = new PopupButton(this.width / 2 - 118, this.height / 2 + 24, 116, 20,
                text(busy ? "deobso.ely.browser" : "deobso.ely.retry"), b -> {
                    if (busy) openBrowser(); else start();
                }, ru.vidtu.ias.legacy.LegacyTooltip.EMPTY);
        browser.active = !busy || device != null;
        this.addRenderableWidget(browser);
        PopupButton copy = new PopupButton(this.width / 2 + 2, this.height / 2 + 24, 116, 20,
                text("deobso.ely.copy"), b -> {
                    if (device != null) this.minecraft.keyboardHandler.setClipboard(device.userCode());
                }, ru.vidtu.ias.legacy.LegacyTooltip.EMPTY);
        copy.active = busy && device != null;
        this.addRenderableWidget(copy);
        this.addRenderableWidget(new PopupButton(this.width / 2 - 75, this.height / 2 + 52, 150, 20,
                net.minecraft.network.chat.CommonComponents.GUI_BACK, b -> onClose(), ru.vidtu.ias.legacy.LegacyTooltip.EMPTY));
    }
    @Override public void tick() {
        super.tick();
        if (!started && !closed) { started = true; start(); }
    }
    private void start() {
        if (closed || busy) return;
        if (login != null) login.close();
        device = null;
        busy = true;
        int attempt = ++attemptId;
        stage("deobso.ely.initializing", ChatFormatting.YELLOW);
        init();
        login = new ElyDeviceLogin(value -> this.minecraft.execute(() -> {
            if (closed || attempt != attemptId) return;
            device = value;
            stage("deobso.ely.waiting", ChatFormatting.YELLOW, value.userCode());
            init();
            openBrowser();
        }));
        login.result().thenApplyAsync(session -> {
            try { return ElyAccount.create(session); }
            catch (java.io.IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
        }, IAS.executor()).whenCompleteAsync((account, error) -> {
            if (closed || attempt != attemptId) return;
            busy = false;
            if (error == null) { closed = true; handler.accept(account); }
            else {
                Throwable cause = error;
                while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
                String reason = cause instanceof ElyOAuth.ApiException api ? api.code() : "request_failed";
                stage("deobso.ely.failed", ChatFormatting.RED, reason);
                init();
            }
        }, this.minecraft);
    }
    private void openBrowser() {
        if (closed || device == null || !busy) return;
        try { net.minecraft.Util.getPlatform().openUri(device.browserUri().toString()); }
        catch (RuntimeException ex) {
            stage("deobso.ely.browserFailed", ChatFormatting.RED);
            init();
        }
    }
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        // Bruh.
        assert this.minecraft != null;

        // Render parent behind.
        if (this.parent != null) {
            pose.pushPose();
            pose.translate(0.0F, 0.0F, -500.0F);
            this.parent.render(pose, 0, 0, delta);
            pose.popPose();
        }

        // Render background and widgets.
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, delta);

        // Render the title.
        pose.pushPose();
        pose.scale(2.0F, 2.0F, 2.0F);
        drawCenteredString(pose, this.font, this.title, this.width / 4, this.height / 4 - 74 / 2, 0xFF_FF_FF_FF);
        pose.popPose();

        if (label == null) label = MultiLineLabel.create(this.font, stage, 236);
        label.renderCentered(pose, this.width / 2, (this.height - label.getLineCount() * 9) / 2 - 14, 9, 0xFF_FF_FF_FF);
    }


    @Override
    public void renderBackground(PoseStack pose) {
        // Bruh.
        assert this.minecraft != null;

        // Render transparent background if parent exists.
        if (this.parent != null) {
            // Render gradient.
            fill(pose, 0, 0, this.width, this.height, 0x80_00_00_00);
        } else {
            super.renderBackground(pose);
        }

        // Render "form".
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        fill(pose, centerX - 125, centerY - 75, centerX + 125, centerY + 75, 0xF8_20_20_30);
        fill(pose, centerX - 124, centerY - 76, centerX + 124, centerY - 75, 0xF8_20_20_30);
        fill(pose, centerX - 124, centerY + 75, centerX + 124, centerY + 76, 0xF8_20_20_30);
    }


    @Override public void removed() {
        closed = true;
        if (login != null) login.close();
        if (device != null && device.userCode().equals(this.minecraft.keyboardHandler.getClipboard())) {
            this.minecraft.keyboardHandler.setClipboard("");
        }
        super.removed();
    }
    @Override public void onClose() {
        closed = true;
        if (login != null) login.close();
        this.minecraft.setScreen(this.parent);
    }
}
