package com.ultracore.core;

import com.ultracore.UltraCore;
import com.ultracore.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * ONE repeating task shared by every chunk-based module. It snapshots the loaded chunks once per cycle and then
 * walks a small, bounded number of them per tick, so no module ever runs a full-world sweep in a single tick.
 * It only ever touches chunks that are already loaded and never loads or generates anything.
 */
public final class ChunkScanner {
    private static final Entity[] NONE = new Entity[0];
    private static final Chunk[] NO_CHUNKS = new Chunk[0];

    private final UltraCore plugin;
    private final List<ChunkVisitor> visitors = new ArrayList<>(6);
    private Chunk[] snapshot;
    private int idx, wait, interval, perTick;
    private long cycles;
    private int errors;

    public ChunkScanner(UltraCore plugin) { this.plugin = plugin; }

    public void register(ChunkVisitor v) { if (!visitors.contains(v)) visitors.add(v); }
    public long cycles() { return cycles; }

    public void start() {
        interval = Math.max(20, plugin.getConfig().getInt("scanner.interval-ticks", 100));
        perTick = Math.max(1, plugin.getConfig().getInt("scanner.chunks-per-tick", 6));
        wait = interval;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
    }

    public void stop() {
        visitors.clear();
        snapshot = null;
    }

    private void tick() {
        if (snapshot == null) {
            if (visitors.isEmpty() || --wait > 0) return;
            snapshot = collect();
            idx = 0;
            for (int i = 0; i < visitors.size(); i++) safeStart(visitors.get(i));
            if (snapshot.length == 0) { finishCycle(); return; }
        }
        int budget = plugin.pressure() >= 2 ? Math.max(1, perTick / 2) : perTick;
        boolean needEnt = false;
        for (int i = 0; i < visitors.size(); i++) needEnt |= visitors.get(i).needsEntities();
        while (idx < snapshot.length && budget-- > 0) {
            Chunk c = snapshot[idx++];
            if (!c.isLoaded()) continue;
            Entity[] ents = NONE;
            if (needEnt && (!Compat.entitiesLoaded || c.isEntitiesLoaded())) ents = c.getEntities();
            for (int i = 0; i < visitors.size(); i++) safeVisit(visitors.get(i), c, ents);
        }
        if (idx >= snapshot.length) finishCycle();
    }

    private void finishCycle() {
        snapshot = null;
        wait = interval;
        cycles++;
        for (int i = 0; i < visitors.size(); i++) {
            try { visitors.get(i).cycleDone(); } catch (Throwable t) { report(t); }
        }
    }

    /** Immediate full pass for a single visitor (used by /ultracore optimize). */
    public void runNow(ChunkVisitor v) {
        Chunk[] all = collect();
        safeStart(v);
        for (Chunk c : all) {
            if (!c.isLoaded()) continue;
            Entity[] ents = NONE;
            if (v.needsEntities() && (!Compat.entitiesLoaded || c.isEntitiesLoaded())) ents = c.getEntities();
            safeVisit(v, c, ents);
        }
        try { v.cycleDone(); } catch (Throwable t) { report(t); }
    }

    private Chunk[] collect() {
        List<World> worlds = Bukkit.getWorlds();
        Chunk[][] parts = new Chunk[worlds.size()][];
        int total = 0;
        for (int i = 0; i < parts.length; i++) {
            World w = worlds.get(i);
            parts[i] = plugin.isExcluded(w) ? NO_CHUNKS : w.getLoadedChunks();
            total += parts[i].length;
        }
        Chunk[] out = new Chunk[total];
        int p = 0;
        for (Chunk[] part : parts) { System.arraycopy(part, 0, out, p, part.length); p += part.length; }
        return out;
    }

    private void safeStart(ChunkVisitor v) {
        try { v.cycleStart(); } catch (Throwable t) { report(t); }
    }

    private void safeVisit(ChunkVisitor v, Chunk c, Entity[] e) {
        try { v.visit(c, e); } catch (Throwable t) { report(t); }
    }

    private void report(Throwable t) {
        if (errors++ < 5) plugin.getLogger().log(Level.WARNING, "Chunk scanner visitor error (continuing)", t);
    }
}
