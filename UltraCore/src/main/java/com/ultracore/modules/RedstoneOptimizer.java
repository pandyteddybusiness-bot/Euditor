package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.Keys;
import com.ultracore.util.LongLongMap;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

/**
 * Item 13: redstone load monitoring and optional shedding.
 * Counts redstone updates per chunk in a short window. A chunk that exceeds the limit is a lag machine (fast
 * clock, runaway loop). Modes:
 *   WARN     (default) log the chunk coordinates, rate-limited. Redstone behavior is NEVER changed.
 *   SUPPRESS opt-in: while server pressure is elevated, updates beyond the limit in that chunk are dropped
 *            (the signal is held at its previous value). This DOES change redstone behavior for that chunk and
 *            is intended only as an emergency brake against clock machines.
 * Paper's alternate-current / vanilla-accurate redstone engines are the right way to make normal redstone cheaper;
 * this module deliberately doesn't try to re-implement them.
 */
public final class RedstoneOptimizer extends AbstractModule implements Listener {
    private final LongLongMap counts = new LongLongMap(64);
    private final LongLongMap lastWarn = new LongLongMap(16);
    private boolean suppress;
    private int limit, windowTicks, warnCooldown;
    private long suppressed, hotChunks;

    public RedstoneOptimizer(UltraCore p) { super(p, "redstone", "RedstoneOptimizer"); }

    @Override
    protected void onEnable() {
        suppress = "SUPPRESS".equalsIgnoreCase(cfg.getString("mode", "WARN"));
        windowTicks = Math.max(10, cfg.getInt("window-ticks", 20));
        limit = Math.max(50, cfg.getInt("max-updates-per-chunk-per-window", 1500));
        warnCooldown = Math.max(200, cfg.getInt("warn-cooldown-ticks", 6000));
        repeat(windowTicks, windowTicks, counts::clear);
        repeat(12000L, 12000L, () -> lastWarn.retainValuesAtLeast(Compat.tick() - warnCooldown));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        Block b = e.getBlock();
        long ck = Keys.chunk(b.getX() >> 4, b.getZ() >> 4) ^ ((long) b.getWorld().getUID().hashCode() << 7);
        long n = counts.get(ck, 0) + 1;
        counts.put(ck, n);
        if (n <= limit) return;
        if (n == limit + 1) {
            hotChunks++;
            int now = Compat.tick();
            if (now - lastWarn.get(ck, -warnCooldown) >= warnCooldown) {
                lastWarn.put(ck, now);
                warn("Redstone hotspot: >" + limit + " updates/" + windowTicks + " ticks near " + b.getWorld().getName()
                        + " " + b.getX() + "," + b.getY() + "," + b.getZ() + " (likely a clock or runaway machine).");
            }
        }
        if (suppress && plugin.pressure() >= 1) {
            e.setNewCurrent(e.getOldCurrent());
            suppressed++;
        }
    }

    @Override
    protected void onDisable() { counts.clear(); lastWarn.clear(); }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - RedstoneOptimizer: hotspots flagged &f").append(hotChunks).append("&7, updates suppressed &f").append(suppressed)
                .append("&7 (mode ").append(suppress ? "SUPPRESS" : "WARN").append(")\n");
    }
}
