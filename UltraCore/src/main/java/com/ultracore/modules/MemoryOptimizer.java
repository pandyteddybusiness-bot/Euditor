package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.module.AbstractModule;

/**
 * Items 1 and 5: keeps UltraCore's own footprint flat and reacts to heap emergencies.
 *  - Leak guard: periodically drops per-player tracking entries for players that are no longer online
 *    (every listener/cache in UltraCore already removes its entries on quit/unload; this is the safety net).
 *  - Emergency response: when the adaptive manager reports CRITICAL pressure it runs every module's one-shot
 *    optimization pass (merge/remove excess items, trim excess mobs, release idle chunks), at most once per cooldown.
 * Item 1 (low RAM by design) is implemented across the code base: primitive-keyed maps (LongLongMap), reusable
 * buffers, no streams/lambdas in hot paths, one shared chunk scanner and one shared player tracker.
 */
public final class MemoryOptimizer extends AbstractModule {
    private long cooldownMs, lastEmergency, prunedTotal, emergencies;
    private boolean emergencyOptimize;

    public MemoryOptimizer(UltraCore p) { super(p, "memory", "MemoryOptimizer"); }

    @Override
    protected void onEnable() {
        cooldownMs = Math.max(10, cfg.getInt("emergency-cooldown-seconds", 120)) * 1000L;
        emergencyOptimize = cfg.getBoolean("emergency-optimize", true);
        long interval = Math.max(200, cfg.getInt("leak-check-interval-ticks", 6000));
        repeat(interval, interval, () -> prunedTotal += plugin.motion().prune());
    }

    @Override
    public void onPressureChange(int oldLevel, int newLevel) {
        if (newLevel < 2 || !emergencyOptimize) return;
        long now = System.currentTimeMillis();
        if (now - lastEmergency < cooldownMs) return;
        lastEmergency = now;
        emergencies++;
        warn("Critical pressure - running emergency optimization pass.");
        String r = plugin.runOptimizers();
        for (String line : r.split("\n")) if (!line.isBlank()) log(line.replaceAll("&.", ""));
    }

    @Override
    public String optimizeNow() {
        int p = plugin.motion().prune();
        prunedTotal += p;
        return "pruned " + p + " stale tracker entries";
    }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - MemoryOptimizer: emergencies handled &f").append(emergencies).append("&7, stale entries pruned &f").append(prunedTotal).append('\n');
    }
}
