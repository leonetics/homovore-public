package dev.leonetic.features.gui;

import dev.leonetic.Homovore;
import dev.leonetic.features.Feature;
import dev.leonetic.features.gui.items.Item;
import dev.leonetic.features.gui.items.SearchBar;
import dev.leonetic.features.gui.items.TextBox;
import dev.leonetic.util.render.GuiFade;
import dev.leonetic.features.gui.items.buttons.ModuleButton;
import dev.leonetic.features.modules.Module;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;

public class HomovoreGui extends Screen {
    private static HomovoreGui INSTANCE;
    private static Color colorClipboard = null;
    private float alpha = 0f;
    private long openTime = 0;
    private boolean closing = false;
    private long closeTime = 0;
    private static final long FADE_DURATION = 150L;

    static {
        INSTANCE = new HomovoreGui();
    }

    private final ArrayList<Widget> widgets = new ArrayList<>();
    private SearchBar searchBar;

    public HomovoreGui() {
        super(Component.literal("Homovore"));
        setInstance();
        load();
    }

    public static HomovoreGui getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new HomovoreGui();
        }
        return INSTANCE;
    }

    public static HomovoreGui getClickGui() {
        return HomovoreGui.getInstance();
    }

    private void setInstance() {
        INSTANCE = this;
    }

    private void load() {
        int spacing = GuiTheme.PANEL_WIDTH + GuiTheme.PANEL_SPACING;
        int x = 6 - spacing;
        for (Module.Category category : Homovore.moduleManager.getCategories()) {
            if (category == Module.Category.HUD) continue;
            if (category == Module.Category.FUNNY && !Homovore.commandManager.isFunnyVisible()) continue;
            Widget panel = new Widget(category.getName(), category, x += spacing, 6, true);
            Homovore.moduleManager.stream()
                    .filter(m -> m.getCategory() == category && !m.hidden)
                    .map(ModuleButton::new)
                    .forEach(panel::addButton);
            this.widgets.add(panel);
        }
        this.widgets.forEach(components -> components.getItems().sort(Comparator.comparing(Feature::getName)));

        for (Widget panel : this.widgets) {
            if (panel.getCategory() == Module.Category.CLIENT) {
                this.searchBar = new SearchBar();
                panel.getItems().add(0, this.searchBar);
                break;
            }
        }
    }

    @Override
    public void init() {
        super.init();
        openTime = System.currentTimeMillis();
        closing = false;
        alpha = 0f;
        GuiNavigator.reset();
    }

    @Override
    public void onClose() {
        if (!closing) {
            closing = true;
            closeTime = System.currentTimeMillis();
            if (searchBar != null) searchBar.clear();
            GuiNavigator.reset();
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        if (!closing) {
            alpha = Math.min(1f, (float) (now - openTime) / FADE_DURATION);
        } else {
            alpha = Math.max(0f, 1f - (float) (now - closeTime) / FADE_DURATION);
            if (alpha <= 0f) {
                minecraft.setScreen(null);
                return;
            }
        }

        GuiFade.alpha = alpha;
        Item.context = context;
        context.fill(0, 0, context.guiWidth(), context.guiHeight(), GuiFade.apply(GuiTheme.SCREEN_DIM));
        float s = getScale();
        int sx = (int) (mouseX / s);
        int sy = (int) (mouseY / s);
        context.pose().pushMatrix();
        context.pose().scale(s, s);
        this.widgets.forEach(components -> components.drawScreen(context, sx, sy, delta));
        context.pose().popMatrix();
        GuiFade.alpha = 1f;
    }

    public static float getScale() {
        return 1f;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (click.x() / s);
        int my = (int) (click.y() / s);
        this.widgets.forEach(components -> components.mouseClicked(mx, my, click.button()));
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (click.x() / s);
        int my = (int) (click.y() / s);
        this.widgets.forEach(components -> components.mouseReleased(mx, my, click.button()));
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (closing) return true;
        float s = getScale();
        int mx = (int) (mouseX / s);
        int my = (int) (mouseY / s);
        for (Widget widget : this.widgets) {
            if (widget.isHoveringBody(mx, my)) {
                widget.scroll(verticalAmount);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (closing) return true;

        if (TextBox.hasActiveFocus()) {
            TextBox.routeKeyPressed(input.input());
            return true;
        }

        if (GuiNavigator.handleKey(input.input())) {
            return true;
        }

        if (input.input() == dev.leonetic.features.modules.client.ClickGuiModule.getInstance().bind.getValue().getKey()) {
            if (System.currentTimeMillis() - openTime > 50) {
                this.onClose();
            }
            return true;
        }

        this.widgets.forEach(component -> component.onKeyPressed(input.input()));
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (closing) return true;
        if (TextBox.hasActiveFocus()) {
            TextBox.routeKeyTyped(input.codepointAsString());
            return true;
        }
        this.widgets.forEach(component -> component.onKeyTyped(input.codepointAsString(), input.modifiers()));
        return super.charTyped(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
    }

    public void reload() {
        this.widgets.clear();
        load();
    }

    public final ArrayList<Widget> getComponents() {
        return this.widgets;
    }

    public int getTextOffset() {
        return -6;
    }

    public static Color getColorClipboard() {
        return colorClipboard;
    }

    public static void setColorClipboard(Color color) {
        colorClipboard = color;
    }
}
