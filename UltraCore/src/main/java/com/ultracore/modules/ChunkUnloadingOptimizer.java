package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.ChunkVisitor;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.Keys;
import com.ultracore.util.LongLongMap;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Item 17: releases idle chunks sooner than vanilla's ticket timeouts, safely.
 * A chunk is requested to unload only if, for the whole grace period, it had no player within view distance + margin,
 * is not force-loaded and has no plugin chunk tickets. The request uses Paper's World#unloadChunkRequest, which the
 * server honours only when nothing else needs the chunk and which saves the chunk normally on unload, so data is never lost.
 * Grace shrinks under pressure; per-cycle request count is capped so the main thread is never flooded.
 */
public final class ChunkUnloadingOptimizer extends AbstractModule implements ChunkVisitor {
    private final Map<UUID, LongLongMap> idle = new HashMap<>();
    private int graceTicks, margin, maxPerCycle, unloadedThisCycle;
    private long cycle, requested;
    private boolean force;

    public ChunkUnloadingOptimizer(UltraCore p) { super(p, "chunkunloading", "ChunkUnloadingOptimizer"); }

    @Override
    protected void onEnable() {
        if (!Compat.unloadRequest) throw new UnsupportedOperationException("World#unloadChunkRequest is missing (Paper required)");
        graceTicks = Math.max(100, cfg.getInt("grace-seconds", 45) * 20);
        margin = Math.max(1, cfg.getInt("keep-radius-extra-chunks", 2));
        maxPerCycle = Math.max(1, cfg.getInt("max-requests-per-cycle", 48));
        plugin.scanner().register(this);
    }

    @Override public boolean needsEntities() { return false; }
    @Override public void cycleStart() { cycle++; unloadedThisCycle = 0; }

    @Override
    public void visit(Chunk chunk, Entity[] unused) {
        World w = chunk.getWorld();
        UUID wid = w.getUID();
        LongLongMap m = idle.computeIfAbsent(wid, k -> new LongLongMap(64));
        int cx = chunk.getX(), cz = chunk.getZ();
        long key = Keys.chunk(cx, cz);
        double radius = (w.getViewDistance() + margin + 1) * 16.0;
        boolean busy = plugin.motion().nearAnyPlayer(wid, cx * 16.0 + 8, cz * 16.0 + 8, radius)
                || chunk.isForceLoaded()
                || (Compat.pluginTickets && !chunk.getPluginChunkTickets().isEmpty());
        if (busy) { m.remove(key); return; }
        int now = Compat.tick();
        long v = m.get(key, -1L);
        if (v < 0 && !force) { m.put(key, (cycle << 32) | (now & 0xFFFFFFFFL)); return; }
        int first = v < 0 ? now - graceTicks : (int) v;
        m.put(key, (cycle << 32) | (first & 0xFFFFFFFFL));
        int grace = plugin.pressure() >= 1 ? graceTicks / 2 : graceTicks;
        if ((force || now - first >= grace) && unloadedThisCycle < (force ? Integer.MAX_VALUE : maxPerCycle)) {
            w.unloadChunkRequest(cx, cz);
            m.remove(key);
            unloadedThisCycle++;
            requested++;
        }
    }

    @Override
    public void cycleDone() {
        long min = cycle << 32;
        for (LongLongMap m : idle.values()) m.retainValuesAtLeast(min); // forget chunks not seen this cycle
    }

    @Override
    protected void onDisable() { idle.clear(); }

    @Override
    public String optimizeNow() {
        long before = requested;
        force = true;
        try { plugin.scanner().runNow(this); } finally { force = false; }
        return "requested unload of " + (requested - before) + " idle chunks (server unloads them when safe)";
    }

    @Override
    public void describe(StringBuilder sb) {
        int tracked = 0;
        for (LongLongMap m : idle.values()) tracked += m.size();
        sb.append("&7 - ChunkUnloading: idle chunks tracked &f").append(tracked).append("&7, unload requests issued &f").append(requested).append('\n');
    }
}
