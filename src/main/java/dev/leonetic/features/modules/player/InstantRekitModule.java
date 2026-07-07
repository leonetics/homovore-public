package dev.leonetic.features.modules.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.inventory.InventoryUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InstantRekitModule extends Module {

    private final Setting<Boolean> topUpStacks = bool("TopUpStacks", true);
    private final Setting<Boolean> closeOnDone = bool("CloseOnDone", false);

    private final Map<Integer, String> kit = new LinkedHashMap<>();

    private boolean firedThisContainer;

    public InstantRekitModule() {
        super("InstantRekit", "Restores a saved kit from an open container in a single tick.", Category.PLAYER);
    }

    public int saveKit() {
        if (nullCheck()) return 0;
        kit.clear();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            kit.put(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return kit.size();
    }

    public boolean hasKit() {
        return !kit.isEmpty();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        if (!isContainerOpen()) {
            firedThisContainer = false;
            return;
        }

        if (firedThisContainer) return;
        firedThisContainer = true;
        rekit();
    }

    private void rekit() {
        if (kit.isEmpty()) {
            Command.sendMessage("{red} No kit saved. Use .savekit while holding your kit.");
            return;
        }
        if (!InventoryUtil.cursor().isEmpty()) return;

        for (Move move : plan()) execute(move);

        if (closeOnDone.getValue()) mc.player.closeContainer();
    }

    private List<Move> plan() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerCount = Math.max(0, menu.slots.size() - 36);
        Usage usage = new Usage(menu, containerCount);
        List<Move> moves = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : kit.entrySet()) {
            int target = entry.getKey();
            Item want = item(entry.getValue());
            if (want == null) continue;

            ItemStack cur = mc.player.getInventory().getItem(target);
            if (!cur.isEmpty() && cur.getItem() == want) continue;

            int src = usage.bestSource(want);
            if (src == -1) continue;
            moves.add(new Move(src, target, false));
            usage.consumeAll(src);
        }

        if (topUpStacks.getValue()) {
            for (Map.Entry<Integer, String> entry : kit.entrySet()) {
                int target = entry.getKey();
                Item want = item(entry.getValue());
                if (want == null) continue;

                ItemStack cur = mc.player.getInventory().getItem(target);
                if (cur.isEmpty() || cur.getItem() != want || !cur.isStackable()) continue;
                int need = cur.getMaxStackSize() - cur.getCount();
                if (need <= 0) continue;

                int src;
                while (need > 0 && (src = usage.bestSource(want)) != -1) {
                    int avail = usage.remaining(src);
                    moves.add(new Move(src, target, true));
                    int used = Math.min(need, avail);
                    usage.consume(src, used);
                    need -= used;
                }
            }
        }
        return moves;
    }

    private void execute(Move move) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerCount = Math.max(0, menu.slots.size() - 36);

        if (!move.topUp && move.target <= 8) {
            InventoryUtil.click(move.source, move.target, ClickType.SWAP);
            return;
        }

        int targetSlot = handlerSlot(move.target, containerCount);
        boolean targetEmpty = menu.getSlot(targetSlot).getItem().isEmpty();
        InventoryUtil.click(move.source, 0, ClickType.PICKUP);
        InventoryUtil.click(targetSlot, 0, ClickType.PICKUP);
        if (!targetEmpty) InventoryUtil.click(move.source, 0, ClickType.PICKUP);
    }

    private static int handlerSlot(int invIndex, int containerCount) {
        return invIndex <= 8
                ? containerCount + 27 + invIndex
                : containerCount + (invIndex - 9);
    }

    private boolean isContainerOpen() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || mc.screen instanceof InventoryScreen) return false;
        if (isEnderChest(screen)) return false;
        AbstractContainerMenu menu = mc.player.containerMenu;
        return menu != null && menu.slots.size() > 36;
    }

    private boolean isEnderChest(AbstractContainerScreen<?> screen) {
        Component title = screen.getTitle();
        if (title.getContents() instanceof TranslatableContents tc && "container.enderchest".equals(tc.getKey())) return true;
        return title.getString().equalsIgnoreCase("Ender Chest");
    }

    private static Item item(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        Identifier key = colon < 0
                ? Identifier.fromNamespaceAndPath("minecraft", id)
                : Identifier.fromNamespaceAndPath(id.substring(0, colon), id.substring(colon + 1));
        return BuiltInRegistries.ITEM.getValue(key);
    }

    private record Move(int source, int target, boolean topUp) {}

    private static final class Usage {
        private final AbstractContainerMenu menu;
        private final int containerCount;
        private final Map<Integer, Integer> remaining = new HashMap<>();

        Usage(AbstractContainerMenu menu, int containerCount) {
            this.menu = menu;
            this.containerCount = containerCount;
        }

        int remaining(int slot) {
            return remaining.computeIfAbsent(slot, i -> menu.getSlot(i).getItem().getCount());
        }

        int bestSource(Item want) {
            int best = -1, bestCount = 0;
            for (int i = 0; i < containerCount; i++) {
                ItemStack s = menu.getSlot(i).getItem();
                if (s.isEmpty() || s.getItem() != want) continue;
                int count = remaining(i);
                if (count > bestCount) {
                    bestCount = count;
                    best = i;
                }
            }
            return best;
        }

        void consume(int slot, int amount) {
            remaining.put(slot, Math.max(0, remaining(slot) - amount));
        }

        void consumeAll(int slot) {
            remaining.put(slot, 0);
        }
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = super.toJson().getAsJsonObject();
        JsonArray array = new JsonArray();
        for (Map.Entry<Integer, String> entry : kit.entrySet()) {
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", entry.getKey());
            slot.addProperty("item", entry.getValue());
            array.add(slot);
        }
        object.add("Kit", array);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        super.fromJson(element);
        kit.clear();
        if (element == null || !element.isJsonObject()) return;
        JsonElement kitElement = element.getAsJsonObject().get("Kit");
        if (kitElement == null || !kitElement.isJsonArray()) return;
        for (JsonElement el : kitElement.getAsJsonArray()) {
            JsonObject slot = el.getAsJsonObject();
            kit.put(slot.get("slot").getAsInt(), slot.get("item").getAsString());
        }
    }
}
