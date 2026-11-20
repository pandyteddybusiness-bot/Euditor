package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.AdaptiveManager;
import com.ultracore.module.AbstractModule;
import org.bukkit.Bukkit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Items 3 and 4: GC pressure management.
 *  - Measures real GC overhead (time spent collecting / wall time) and warns, rate-limited, when it is too high.
 *    The same measurement also feeds the adaptive pressure level, so other modules shed load when GC thrashes.
 *  - Idle collection: when the server has been empty for a while and the heap is fairly full, runs ONE
 *    System.gc() (rate-limited). Nobody is online so the pause is invisible; this returns memory to the pool
 *    before the next player joins. Never runs while players are online unless /ultracore optimize is issued
 *    with an empty server.
 *  - JVM flag audit at startup: flags a missing -Xmx or -XX:+DisableExplicitGC (which would make idle GC a no-op).
 * Allocation reduction (item 4) is a coding rule applied everywhere else in the plugin (see MemoryOptimizer).
 */
public final class GarbageCollectionOptimizer extends AbstractModule {
    private boolean idleGc;
    private long idleMs, gcCooldownMs, emptySince = -1, lastGc, lastWarn, gcRuns;
    private double warnPct, idleMinHeapPct;

    public GarbageCollectionOptimizer(UltraCore p) { super(p, "gc", "GarbageCollectionOptimizer"); }

    @Override
    protected void onEnable() {
        idleGc = cfg.getBoolean("idle-gc", true);
        idleMs = Math.max(1, cfg.getInt("idle-minutes", 10)) * 60_000L;
        gcCooldownMs = Math.max(1, cfg.getInt("idle-gc-cooldown-minutes", 30)) * 60_000L;
        idleMinHeapPct = cfg.getDouble("idle-gc-min-heap-percent", 50);
        warnPct = cfg.getDouble("warn-overhead-percent", 10);
        auditFlags();
        repeat(1200L, 1200L, this::check);
    }

    private void auditFlags() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean xmx = false;
        for (String a : args) {
            if (a.startsWith("-Xmx")) xmx = true;
            if (a.equals("-XX:+DisableExplicitGC") && idleGc) warn("-XX:+DisableExplicitGC is set: idle GC will be a no-op.");
        }
        if (!xmx) warn("No -Xmx flag found; the heap size is unbounded by your launch script. Set -Xms/-Xmx explicitly (see README).");
    }

    private void check() {
        long now = System.currentTimeMillis();
        boolean empty = Bukkit.getOnlinePlayers().isEmpty();
        if (!empty) emptySince = -1; else if (emptySince < 0) emptySince = now;
        AdaptiveManager a = plugin.adaptive();
        if (a.gcOverheadPct > warnPct && now - lastWarn > 600_000L) {
            lastWarn = now;
            warn(String.format("GC overhead is %.1f%% of wall time. The heap is too small for the current load; lower view/simulation distance or entity limits.", a.gcOverheadPct));
        }
        if (idleGc && empty && now - emptySince >= idleMs && now - lastGc >= gcCooldownMs && a.heapPct >= idleMinHeapPct) runGc();
    }

    private void runGc() {
        long t = System.nanoTime();
        System.gc();
        lastGc = System.currentTimeMillis();
        gcRuns++;
        log("Idle GC completed in " + (System.nanoTime() - t) / 1_000_000L + " ms.");
    }

    @Override
    public String optimizeNow() {
        if (!Bukkit.getOnlinePlayers().isEmpty()) return "skipped explicit GC (players online)";
        runGc();
        return "explicit GC executed (server empty)";
    }

    @Override
    public void describe(StringBuilder sb) {
        long cnt = 0, ms = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getCollectionCount() > 0) cnt += b.getCollectionCount();
            if (b.getCollectionTime() > 0) ms += b.getCollectionTime();
        }
        sb.append("&7 - GC: &f").append(cnt).append("&7 collections, &f").append(ms).append(" ms&7 total since JVM start; idle GCs by UltraCore: &f").append(gcRuns).append('\n');
    }
}
