package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.ChunkVisitor;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.Keys;
import com.ultracore.util.LongLongMap;
import com.ultracore.util.Msg;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Item 14: bounds the number of ticking block entities per chunk, which is what actually costs time.
 *  - Placement limits: placing a hopper/furnace/etc. beyond the per-chunk limit is denied (players with
 *    ultracore.bypass.blockentity are exempt). Existing blocks are never removed or modified.
 *  - Hotspot detection: chunks whose total block-entity count exceeds a threshold are logged (rate-limited)
 *    so admins can find the lag source.
 * Paper already skips ticking for many idle block entities; this limits how many can pile up in one place.
 */
public final class BlockEntityOptimizer extends AbstractModule implements Listener, ChunkVisitor {
    private final Map<Material, Integer> limits = new EnumMap<>(Material.class);
    private final LongLongMap lastWarn = new LongLongMap(16);
    private int warnTotal, warnCooldown;
    private long denied, hotChunks;

    public BlockEntityOptimizer(UltraCore p) { super(p, "blockentity", "BlockEntityOptimizer"); }

    @Override
    protected void onEnable() {
        limits.clear();
        ConfigurationSection s = cfg.getConfigurationSection("limits-per-chunk");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                Material m = Material.matchMaterial(k.toUpperCase(Locale.ROOT));
                int v = s.getInt(k);
                if (m == null) warn("Unknown material in limits-per-chunk: " + k);
                else if (v > 0) limits.put(m, v);
            }
        }
        warnTotal = cfg.getInt("warn-total-per-chunk", 150);
        warnCooldown = Math.max(600, cfg.getInt("warn-cooldown-ticks", 12000));
        if (warnTotal > 0) plugin.scanner().register(this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material type = e.getBlockPlaced().getType();
        Integer lim = limits.get(type);
        if (lim == null || e.getPlayer().hasPermission("ultracore.bypass.blockentity")) return;
        int count = 0;
        for (BlockState bs : e.getBlockPlaced().getChunk().getTileEntities(false)) if (bs.getType() == type) count++;
        if (count > lim) { // the block being placed is already counted
            e.setCancelled(true);
            denied++;
            Msg.send(e.getPlayer(), "&cThis chunk already has the maximum of " + lim + " " + type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "s.");
        }
    }

    @Override public boolean needsEntities() { return false; }

    @Override
    public void visit(Chunk chunk, Entity[] unused) {
        BlockState[] tes = chunk.getTileEntities(false);
        if (tes.length <= warnTotal) return;
        long key = Keys.chunk(chunk.getX(), chunk.getZ()) ^ ((long) chunk.getWorld().getUID().hashCode() << 5);
        int now = Compat.tick();
        if (now - lastWarn.get(key, -warnCooldown) < warnCooldown) return;
        lastWarn.put(key, now);
        hotChunks++;
        warn("Block-entity hotspot: " + tes.length + " in chunk " + chunk.getX() + "," + chunk.getZ() + " of " + chunk.getWorld().getName()
                + " (block " + (chunk.getX() << 4) + "," + (chunk.getZ() << 4) + ").");
    }

    @Override
    protected void onDisable() { lastWarn.clear(); }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - BlockEntityOptimizer: placements denied &f").append(denied).append("&7, hotspots flagged &f").append(hotChunks).append('\n');
    }
}
