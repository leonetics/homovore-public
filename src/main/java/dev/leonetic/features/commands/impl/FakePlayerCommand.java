package dev.leonetic.features.commands.impl;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.leonetic.features.commands.Command;
import dev.leonetic.manager.CommandManager;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class FakePlayerCommand extends Command {

    private static final byte TOTEM_POP_EVENT_ID = 35;

    private static final EquipmentSlot[] COPIED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final List<RemotePlayer> spawned = new ArrayList<>();
    private int nextId = -13337;

    public FakePlayerCommand() {
        super("fakeplayer", "fp");
        setDescription("Spawns a client-side fake player to test on");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.executes(ctx -> spawn("FakePlayer"))
                .then(literal("remove")
                        .executes(ctx -> removeAll()))
                .then(literal("pop")
                        .executes(ctx -> popAll()))
                .then(literal("swim")
                        .executes(ctx -> toggleSwim()))
                .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> spawn(StringArgumentType.getString(ctx, "name"))));
    }

    private int popAll() {
        if (nullCheck() || mc.getConnection() == null) {
            return fail("You need to be in a world to do that.");
        }

        int popped = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            mc.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(fake, TOTEM_POP_EVENT_ID));
            popped++;
        }

        if (popped == 0) {
            return fail("No fake players to pop. Spawn one first.");
        }
        return success("Popped %s fake player(s)", popped);
    }

    private int toggleSwim() {
        if (nullCheck()) {
            return fail("You need to be in a world to do that.");
        }

        boolean anyStanding = false;
        int found = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            found++;
            if (fake.getPose() != Pose.SWIMMING) anyStanding = true;
        }
        if (found == 0) {
            return fail("No fake players. Spawn one first.");
        }

        Pose pose = anyStanding ? Pose.SWIMMING : Pose.STANDING;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            fake.setPose(pose);
            fake.refreshDimensions();
        }
        return success(pose == Pose.SWIMMING
                ? "Fake player(s) now in swim pose."
                : "Fake player(s) now standing.");
    }

    private int spawn(String name) {
        if (nullCheck()) {
            return fail("You need to be in a world to do that.");
        }

        int despawned = despawnAll();

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        RemotePlayer fake = new RemotePlayer(mc.level, profile);
        fake.setId(nextId--);
        fake.copyPosition(mc.player);
        fake.setYHeadRot(mc.player.getYHeadRot());
        fake.setHealth(20f);
        for (EquipmentSlot slot : COPIED_SLOTS) {
            fake.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
        }
        mc.level.addEntity(fake);
        spawned.add(fake);
        if (despawned > 0) {
            return success("Replaced fake player with {green} %s", name);
        }
        return success("Spawned fake player {green} %s", name);
    }

    private int removeAll() {
        if (nullCheck()) {
            spawned.clear();
            return fail("You need to be in a world to do that.");
        }
        int removed = despawnAll();
        if (removed == 0) {
            return success("No fake players to remove.");
        }
        return success("Removed %s fake player(s)", removed);
    }

    private int despawnAll() {
        int removed = 0;
        Iterator<RemotePlayer> it = spawned.iterator();
        while (it.hasNext()) {
            RemotePlayer fake = it.next();
            it.remove();
            if (fake.level() != mc.level) continue;
            mc.level.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
            removed++;
        }
        return removed;
    }
}
