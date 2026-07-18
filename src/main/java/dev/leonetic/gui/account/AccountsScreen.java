package dev.leonetic.gui.account;

import com.mojang.authlib.GameProfile;
import dev.leonetic.Homovore;
import dev.leonetic.manager.account.Account;
import dev.leonetic.manager.account.MicrosoftAuthService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class AccountsScreen extends Screen {
    private static final int LIST_WIDTH = 332;
    private static final int ROW_HEIGHT = 28;
    private static final int FACE_SIZE = 20;
    private static final int PADDING = 4;

    private final Screen parent;
    private final List<AccountRow> rows = new ArrayList<>();
    private boolean authenticating;
    private String statusMessage;
    private long statusMessageTime;
    private int scrollOffset;
    private int listX;

    private record AccountRow(Account account, Supplier<PlayerSkin> skinSupplier) {}

    public AccountsScreen(Screen parent) {
        super(Component.literal("Account Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        for (Account account : Homovore.accountManager.getAccounts()) {
            Supplier<PlayerSkin> skin = loadSkin(account);
            rows.add(new AccountRow(account, skin));
        }

        listX = (width - LIST_WIDTH) / 2;
        int cx = width / 2;
        int by = height - 60;

        addRenderableWidget(Button.builder(
                Component.literal(authenticating ? "Signing in..." : "Add Microsoft"),
                b -> startMicrosoftAuth()
        ).bounds(cx - 112, by, 110, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Login"),
                b -> loginSelected()
        ).bounds(cx + 2, by, 110, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Delete"),
                b -> deleteSelected()
        ).bounds(cx - 112, by + 22, 110, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Back"),
                b -> minecraft.setScreen(parent)
        ).bounds(cx + 2, by + 22, 110, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        int listY = 28;
        int visibleHeight = height - 64 - 32 - listY;

        if (rows.isEmpty()) {
            graphics.drawString(minecraft.font, Component.literal("No accounts. Add one above."), listX + 6, listY + 6, 0xFF888888, false);
        } else {
            graphics.fill(listX, listY, listX + LIST_WIDTH, listY + Math.min(rows.size() * ROW_HEIGHT, visibleHeight), 0x44000000);

            for (int i = scrollOffset; i < rows.size(); i++) {
                int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
                if (rowY + ROW_HEIGHT > listY + visibleHeight) break;
                if (rowY < listY) continue;

                AccountRow row = rows.get(i);
                boolean selected = Homovore.accountManager.getCurrentAccount() != null
                        && Homovore.accountManager.getCurrentAccount().getName().equalsIgnoreCase(row.account().getName());
                boolean hovered = mouseX >= listX && mouseX <= listX + LIST_WIDTH && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;

                if (hovered) {
                    graphics.fill(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT, 0x22FFFFFF);
                }
                if (selected) {
                    graphics.fill(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT, 0x3300FF00);
                }

                int faceX = listX + PADDING;
                int faceY = rowY + (ROW_HEIGHT - FACE_SIZE) / 2;

                if (row.skinSupplier() != null) {
                    PlayerSkin skin = row.skinSupplier().get();
                    PlayerFaceRenderer.draw(graphics, skin, faceX, faceY, FACE_SIZE);
                }

                int textColor = selected ? 0xFF55FF55 : 0xFFFFFFFF;
                graphics.drawString(minecraft.font, row.account().getName(),
                        faceX + FACE_SIZE + PADDING,
                        rowY + (ROW_HEIGHT - minecraft.font.lineHeight) / 2,
                        textColor, false);
            }
        }

        Account current = Homovore.accountManager.getCurrentAccount();
        if (current != null) {
            graphics.drawString(minecraft.font, Component.literal("Current: " + current.getName()), 4, 4, 0xFF55FF55, false);
        }

        if (statusMessage != null && System.currentTimeMillis() - statusMessageTime < 5000) {
            graphics.drawString(minecraft.font, Component.literal(statusMessage), 4, 16, 0xFF888888, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;

        double mx = event.x();
        double my = event.y();

        if (event.button() == 0) {
            int listY = 28;
            int visibleHeight = height - 64 - 32 - listY;

            for (int i = scrollOffset; i < rows.size(); i++) {
                int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
                if (rowY + ROW_HEIGHT > listY + visibleHeight) break;
                if (rowY < listY) continue;

                if (mx >= listX && mx <= listX + LIST_WIDTH && my >= rowY && my <= rowY + ROW_HEIGHT) {
                    Homovore.accountManager.refreshAndSwitch(rows.get(i).account());
                    setStatus("Switched to " + rows.get(i).account().getName());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int maxScroll = Math.max(0, rows.size() - (height - 64 - 32 - 28) / ROW_HEIGHT);
        scrollOffset = Math.clamp(scrollOffset - (int) Math.round(vertical), 0, maxScroll);
        return true;
    }

    private void loginSelected() {
        Account current = Homovore.accountManager.getCurrentAccount();
        if (current != null) {
            boolean ok = Homovore.accountManager.refreshAndSwitch(current);
            setStatus(ok ? "Switched" : "Failed");
        } else {
            setStatus("Click an account first");
        }
    }

    private void deleteSelected() {
        Account current = Homovore.accountManager.getCurrentAccount();
        if (current != null) {
            Homovore.accountManager.remove(current.getName());
            init();
            setStatus("Removed " + current.getName());
        } else {
            setStatus("Click an account first");
        }
    }

    private void startMicrosoftAuth() {
        if (authenticating) return;
        authenticating = true;
        setStatus("Opening browser for Microsoft login...");
        rebuildWidgets();

        CompletableFuture<MicrosoftAuthService.AuthResult> future = MicrosoftAuthService.login();
        future.whenCompleteAsync((profile, ex) -> {
            if (ex != null) {
                setStatus("Auth error: " + ex.getMessage());
                authenticating = false;
                rebuildWidgets();
                return;
            }
            if (profile == null) {
                setStatus("Cancelled.");
                authenticating = false;
                rebuildWidgets();
                return;
            }
            try {
                Account account = new Account(
                        profile.username(), profile.uuid(),
                        profile.accessToken(), profile.authData(),
                        System.currentTimeMillis()
                );
                Homovore.accountManager.add(account);
                Homovore.accountManager.refreshAndSwitch(account);
                init();
                setStatus("Logged in as " + profile.username());
            } catch (Exception e) {
                Homovore.LOGGER.error("Microsoft auth failed", e);
                setStatus("Auth failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
            }
            authenticating = false;
            rebuildWidgets();
        }, minecraft::execute);
    }

    private void setStatus(String msg) {
        statusMessage = msg;
        statusMessageTime = System.currentTimeMillis();
    }

    private Supplier<PlayerSkin> loadSkin(Account account) {
        try {
            UUID uuid = UUID.fromString(account.getUuid());
            GameProfile profile = new GameProfile(uuid, account.getName());
            return Minecraft.getInstance().getSkinManager().createLookup(profile, false);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
