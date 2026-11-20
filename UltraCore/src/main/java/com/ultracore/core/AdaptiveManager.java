package com.ultracore.core;

import com.ultracore.UltraCore;
import com.ultracore.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;

/**
 * Samples a handful of cheap metrics every few seconds (default 10 s) and maps them to a pressure level:
 * 0 = normal, 1 = elevated, 2 = critical. Hysteresis: a level is entered only after N consecutive bad samples and
 * left only after M consecutive samples that are clearly below the *exit* thresholds (which are stricter than the
 * enter thresholds), so settings never flap.
 */
public final class AdaptiveManager {
    public double tps = 20, mspt = -1, heapPct, gcOverheadPct;
    public long heapUsedMb, heapMaxMb, heapCommittedMb;
    public int chunks, entities;

    private final UltraCore plugin;
    private boolean enabled;
    private int level, up, down, confirm, recover;
    private double tpsWarn, tpsCrit, msptWarn, msptCrit, heapWarn, heapCrit, gcWarn, gcCrit;
    private MemoryPoolMXBean oldGen;
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private long lastGcMs = -1, lastWallMs;

    public AdaptiveManager(UltraCore plugin) { this.plugin = plugin; }

    public int level() { return enabled ? level : 0; }
    public boolean enabled() { return enabled; }
    public static String levelName(int l) { return l == 0 ? "normal" : l == 1 ? "elevated" : "critical"; }

    public void start() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("adaptive.enabled", true);
        level = 0; up = 0; down = 0;
        confirm = Math.max(1, c.getInt("adaptive.confirm-samples", 2));
        recover = Math.max(1, c.getInt("adaptive.recover-samples", 3));
        tpsWarn = c.getDouble("adaptive.tps-warn", 18.5);
        tpsCrit = c.getDouble("adaptive.tps-critical", 16.0);
        msptWarn = c.getDouble("adaptive.mspt-warn", 40);
        msptCrit = c.getDouble("adaptive.mspt-critical", 50);
        heapWarn = c.getDouble("adaptive.heap-warn-percent", 80);
        heapCrit = c.getDouble("adaptive.heap-critical-percent", 90);
        gcWarn = c.getDouble("adaptive.gc-overhead-warn-percent", 8);
        gcCrit = c.getDouble("adaptive.gc-overhead-critical-percent", 20);
        oldGen = null;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            String n = p.getName();
            if (p.getType() == java.lang.management.MemoryType.HEAP && (n.contains("Old Gen") || n.contains("Tenured"))) oldGen = p;
        }
        long interval = Math.max(40, c.getInt("adaptive.interval-ticks", 200));
        sample();
        if (enabled) Bukkit.getScheduler().runTaskTimer(plugin, this::tickSample, interval, interval);
    }

    private void tickSample() {
        sample();
        int raw = evaluate(false), low = evaluate(true);
        int old = level;
        if (raw > level) {
            down = 0;
            if (++up >= confirm) { level = raw; up = 0; }
        } else up = 0;
        if (raw <= level && low < level) {
            if (++down >= recover) { level--; down = 0; }
        } else if (raw > level || low >= level) down = 0;
        if (level != old) {
            plugin.getLogger().info("Pressure level " + levelName(old) + " -> " + levelName(level) + String.format(
                    " (TPS %.1f, MSPT %.1f, heap %.0f%%, GC %.1f%%)", tps, mspt, heapPct, gcOverheadPct));
            plugin.dispatchPressure(old, level);
        }
    }

    private int evaluate(boolean exit) {
        int l = 0;
        double tw = exit ? tpsWarn + 0.5 : tpsWarn, tc = exit ? tpsCrit + 1.0 : tpsCrit;
        if (tps < tc) l = 2; else if (tps < tw) l = 1;
        if (mspt > 0) {
            double mw = exit ? msptWarn * 0.85 : msptWarn, mc = exit ? msptCrit * 0.85 : msptCrit;
            if (mspt > mc) l = 2; else if (mspt > mw) l = Math.max(l, 1);
        }
        double hw = exit ? heapWarn - 5 : heapWarn, hc = exit ? heapCrit - 5 : heapCrit;
        if (heapPct > hc) l = 2; else if (heapPct > hw) l = Math.max(l, 1);
        double gw = exit ? gcWarn * 0.7 : gcWarn, gc = exit ? gcCrit * 0.7 : gcCrit;
        if (gcOverheadPct > gc) l = 2; else if (gcOverheadPct > gw) l = Math.max(l, 1);
        return l;
    }

    /** Cheap: a handful of MXBean/Paper getters, no per-entity or per-chunk iteration. */
    public void sample() {
        try {
            if (Compat.tps) tps = Math.min(20.0, Bukkit.getTPS()[0]);
            if (Compat.avgTickTime) mspt = Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {}
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        heapUsedMb = used >> 20; heapMaxMb = max >> 20; heapCommittedMb = rt.totalMemory() >> 20;
        // Old-gen occupancy is a far better "am I running out" signal than total used, which includes garbage.
        heapPct = 100.0 * (oldGen != null ? oldGen.getUsage().getUsed() : used) / max;
        int c = 0, e = 0;
        for (World w : Bukkit.getWorlds()) {
            try {
                c += Compat.chunkCount ? w.getChunkCount() : w.getLoadedChunks().length;
                e += w.getEntityCount();
            } catch (Throwable ignored) {}
        }
        chunks = c; entities = e;
        long gcMs = 0;
        for (GarbageCollectorMXBean b : gcBeans) { long t = b.getCollectionTime(); if (t > 0) gcMs += t; }
        long now = System.currentTimeMillis();
        if (lastGcMs >= 0 && now > lastWallMs) gcOverheadPct = 100.0 * (gcMs - lastGcMs) / (now - lastWallMs);
        lastGcMs = gcMs; lastWallMs = now;
    }
}
