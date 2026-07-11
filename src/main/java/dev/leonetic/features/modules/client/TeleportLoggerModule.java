package dev.leonetic.features.modules.client;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Logs movement prediction state, outgoing move packets, and server position/look
 * corrections (setbacks / teleports). Output goes to the client log, matching the
 * setback diagnostics in SpeedMineModule.
 */
public class TeleportLoggerModule extends Module {

    private static final double MOVEMENT_EPSILON = 1.0E-4;
    private static final float ROTATION_EPSILON = 0.01f;

    private final Setting<Boolean> tickState     = bool("TickState", true).setPage("State");
    private final Setting<Integer> tickInterval  = tickInterval();
    private final Setting<Boolean> onlyWhenMoving = onlyWhenMoving();

    private final Setting<Boolean> logMovePackets     = bool("MovePackets", false).setPage("Packets");
    private final Setting<Boolean> logCorrections     = bool("Corrections", true).setPage("Packets");
    private final Setting<Boolean> logVelocityPackets = bool("VelocityPackets", false).setPage("Packets");

    // Last known position (for per-tick delta).
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    // Last outgoing move-packet position (for "packet delta").
    private double sentX = Double.NaN, sentY = Double.NaN, sentZ = Double.NaN;
    private int ticks;

    public TeleportLoggerModule() {
        super("TeleportLogger", "Logs movement prediction, move packets, and server setbacks/teleports", Category.CLIENT);
    }

    private Setting<Integer> tickInterval() {
        Setting<Integer> s = num("TickInterval", 1, 1, 20).setPage("State");
        s.setVisibility(v -> tickState.getValue());
        return s;
    }

    private Setting<Boolean> onlyWhenMoving() {
        Setting<Boolean> s = bool("OnlyWhenMoving", true).setPage("State");
        s.setVisibility(v -> tickState.getValue());
        return s;
    }

    @Override
    public void onEnable() {
        ticks = 0;
        syncLastPos();
        sentX = sentY = sentZ = Double.NaN;
    }

    @Override
    public void onDisable() {
        lastX = lastY = lastZ = Double.NaN;
        sentX = sentY = sentZ = Double.NaN;
        ticks = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) {
            lastX = lastY = lastZ = Double.NaN;
            ticks = 0;
            return;
        }

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        double dx = Double.isNaN(lastX) ? 0.0 : x - lastX;
        double dy = Double.isNaN(lastY) ? 0.0 : y - lastY;
        double dz = Double.isNaN(lastZ) ? 0.0 : z - lastZ;
        lastX = x;
        lastY = y;
        lastZ = z;

        if (!tickState.getValue()) return;

        ticks++;
        if (ticks % tickInterval.getValue() != 0) return;

        Vec3 vel = mc.player.getDeltaMovement();
        Vec2 move = mc.player.input.getMoveVector();
        float forward = move.y;
        float sideways = move.x;

        // Delta versus the last position we actually sent to the server.
        double pdx = Double.isNaN(sentX) ? 0.0 : x - sentX;
        double pdy = Double.isNaN(sentY) ? 0.0 : y - sentY;
        double pdz = Double.isNaN(sentZ) ? 0.0 : z - sentZ;

        float serverYaw = Homovore.rotationManager.getServerYaw();
        float serverPitch = Homovore.rotationManager.getServerPitch();
        float yawDesync = Mth.wrapDegrees(serverYaw - mc.player.getYRot());
        float pitchDesync = serverPitch - mc.player.getXRot();

        boolean active = Math.abs(forward) > MOVEMENT_EPSILON
                || Math.abs(sideways) > MOVEMENT_EPSILON
                || Math.abs(dx) > MOVEMENT_EPSILON
                || Math.abs(dy) > MOVEMENT_EPSILON
                || Math.abs(dz) > MOVEMENT_EPSILON
                || Math.abs(vel.x) > MOVEMENT_EPSILON
                || Math.abs(vel.y) > MOVEMENT_EPSILON
                || Math.abs(vel.z) > MOVEMENT_EPSILON
                || Math.abs(pdx) > MOVEMENT_EPSILON
                || Math.abs(pdy) > MOVEMENT_EPSILON
                || Math.abs(pdz) > MOVEMENT_EPSILON
                || Math.abs(yawDesync) > ROTATION_EPSILON
                || Math.abs(pitchDesync) > ROTATION_EPSILON
                || mc.player.isFallFlying();

        if (onlyWhenMoving.getValue() && !active) return;

        info(String.format(Locale.ROOT,
                "tick pos=%s d=%s vel=%s input=(%.2f,%.2f,j=%s,s=%s) ground=%s fly=%s pkt-d=%s rot c=(%.2f,%.2f) s=(%.2f,%.2f) rot-d=(%.2f,%.2f)",
                fmt(x, y, z),
                fmt(dx, dy, dz),
                fmt(vel.x, vel.y, vel.z),
                forward, sideways,
                mc.options.keyJump.isDown(),
                mc.options.keyShift.isDown(),
                mc.player.onGround(),
                mc.player.isFallFlying(),
                fmt(pdx, pdy, pdz),
                mc.player.getYRot(), mc.player.getXRot(),
                serverYaw, serverPitch,
                yawDesync, pitchDesync));
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket move)) return;

        if (move.hasPosition()) {
            sentX = move.getX(mc.player.getX());
            sentY = move.getY(mc.player.getY());
            sentZ = move.getZ(mc.player.getZ());
        }

        if (!logMovePackets.getValue()) return;

        info(String.format(Locale.ROOT,
                "c2s move[%s] pos=%s rot=(%.2f,%.2f) ground=%s",
                movePacketType(move),
                fmt(move.getX(mc.player.getX()), move.getY(mc.player.getY()), move.getZ(mc.player.getZ())),
                move.getYRot(mc.player.getYRot()),
                move.getXRot(mc.player.getXRot()),
                move.isOnGround()));
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (logCorrections.getValue() && event.getPacket() instanceof ClientboundPlayerPositionPacket packet) {
            var relatives = packet.relatives();
            PositionMoveRotation change = packet.change();
            Vec3 pos = change.position();

            double tx = relatives.contains(Relative.X) ? mc.player.getX() + pos.x : pos.x;
            double ty = relatives.contains(Relative.Y) ? mc.player.getY() + pos.y : pos.y;
            double tz = relatives.contains(Relative.Z) ? mc.player.getZ() + pos.z : pos.z;
            float tyaw = relatives.contains(Relative.Y_ROT) ? mc.player.getYRot() + change.yRot() : change.yRot();
            float tpitch = relatives.contains(Relative.X_ROT) ? mc.player.getXRot() + change.xRot() : change.xRot();

            info(String.format(Locale.ROOT,
                    "s2c correction id=%d flags=%s target=%s delta=%s rot=(%.2f,%.2f) rot-d=(%.2f,%.2f)",
                    packet.id(),
                    relatives,
                    fmt(tx, ty, tz),
                    fmt(tx - mc.player.getX(), ty - mc.player.getY(), tz - mc.player.getZ()),
                    tyaw, tpitch,
                    Mth.wrapDegrees(tyaw - mc.player.getYRot()),
                    tpitch - mc.player.getXRot()));
        }

        if (logVelocityPackets.getValue()
                && event.getPacket() instanceof ClientboundSetEntityMotionPacket packet
                && packet.getId() == mc.player.getId()) {
            Vec3 v = packet.getMovement();
            info(String.format(Locale.ROOT, "s2c velocity vel=%s", fmt(v.x, v.y, v.z)));
        }
    }

    private void syncLastPos() {
        if (mc.player == null) {
            lastX = lastY = lastZ = Double.NaN;
        } else {
            lastX = mc.player.getX();
            lastY = mc.player.getY();
            lastZ = mc.player.getZ();
        }
    }

    private String movePacketType(ServerboundMovePlayerPacket packet) {
        if (packet.hasPosition() && packet.hasRotation()) return "full";
        if (packet.hasPosition()) return "pos";
        return packet.hasRotation() ? "look" : "ground";
    }

    private String fmt(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", x, y, z);
    }

    private void info(String message) {
        Homovore.LOGGER.info("[TeleportLogger] {}", message);
    }
}
