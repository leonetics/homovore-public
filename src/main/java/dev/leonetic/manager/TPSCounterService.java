package dev.leonetic.manager;

import dev.leonetic.event.impl.network.DisconnectEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.Feature;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

public class TPSCounterService extends Feature {
    private final float[] tickRates = new float[20];
    private int nextIndex = 0;
    private int countTick = 0;
    private long lastTimeUpdate = -1;

    public void init() {
        EVENT_BUS.register(this);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        nextIndex = 0;
        countTick = 0;
        lastTimeUpdate = -1;
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSetTimePacket)) return;

        long now = System.currentTimeMillis();

        if (lastTimeUpdate != -1) {
            float elapsed = (now - lastTimeUpdate) / 1000.0f;
            float tps = 20.0f / elapsed;
            tickRates[nextIndex % tickRates.length] = Math.min(Math.max(tps, 0.0f), 20.0f);
            nextIndex++;
            countTick = Math.min(countTick + 1, tickRates.length);
        }

        lastTimeUpdate = now;
    }

    public float getAverageTPS() {
        if (countTick == 0) return 20.0f;

        float sum = 0.0f;
        int valid = 0;
        for (int i = 0; i < countTick; i++) {
            float t = tickRates[i];
            if (t > 0.0f) {
                sum += t;
                valid++;
            }
        }
        return valid == 0 ? 20.0f : Math.min(Math.max(sum / valid, 0.0f), 20.0f);
    }

    public float getLatestTPS() {
        if (countTick == 0) return 20.0f;

        int last = (nextIndex - 1 + tickRates.length) % tickRates.length;
        return Math.min(Math.max(tickRates[last], 0.0f), 20.0f);
    }

    public float getMinTPS() {
        if (countTick == 0) return 20.0f;

        float min = 20.0f;
        for (int i = 0; i < countTick; i++) {
            float t = tickRates[i];
            if (t > 0.0f && t < min) {
                min = t;
            }
        }
        return Math.min(Math.max(min, 0.0f), 20.0f);
    }
}
