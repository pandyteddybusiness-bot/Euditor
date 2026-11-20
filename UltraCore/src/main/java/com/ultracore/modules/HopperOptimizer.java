package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.Keys;
import com.ultracore.util.LongLongMap;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * Item 12: pressure-adaptive hopper throttling.
 * Each hopper may perform one transfer per "interval" ticks. At normal pressure the interval equals vanilla's
 * 8-tick cooldown, so behavior is identical. Under elevated/critical pressure the interval grows (x2, x3): hoppers
 * keep moving items, only slower, and nothing is ever deleted or lost: a throttled transfer is simply cancelled
 * and the hopper retries after its cooldown. Only hopper blocks are affected (not hopper minecarts or other
 * inventory movers). Paper's own hopper settings (README) give larger static savings; this adds load-shedding on top.
 */
public final class HopperOptimizer extends AbstractModule implements Listener {
    private final LongLongMap last = new LongLongMap(256);
    private int baseInterval;
    private long throttled;

    public HopperOptimizer(UltraCore p) { super(p, "hopper", "HopperOptimizer"); }

    @Override
    protected void onEnable() {
        baseInterval = Math.max(1, cfg.getInt("base-interval-ticks", 8));
        long prune = Math.max(600, cfg.getInt("prune-interval-ticks", 6000));
        repeat(prune, prune, () -> last.retainValuesAtLeast(Compat.tick() - 1200L));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        int level = plugin.pressure();
        if (level == 0) return;
        Inventory init = e.getInitiator();
        if (init.getType() != InventoryType.HOPPER) return;
        Location loc = init.getLocation();
        if (loc == null || loc.getWorld() == null || plugin.isExcluded(loc.getWorld())) return;
        long key = Keys.block(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()) ^ ((long) loc.getWorld().getUID().hashCode() << 1);
        int now = Compat.tick();
        long prev = last.get(key, Long.MIN_VALUE);
        if (prev != Long.MIN_VALUE && now - prev < (long) baseInterval * (1 + level)) {
            e.setCancelled(true);
            throttled++;
            return;
        }
        last.put(key, now);
    }

    @Override
    protected void onDisable() { last.clear(); }

    @Override
    public String optimizeNow() { last.retainValuesAtLeast(Compat.tick() - 1200L); return "tracking table trimmed to " + last.size() + " hoppers"; }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - HopperOptimizer: transfers delayed under load &f").append(throttled).append("&7, hoppers tracked &f").append(last.size()).append('\n');
    }
}
