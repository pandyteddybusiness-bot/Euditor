package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.module.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Item 20: disk-write shaping that cannot lose data.
 * Always on (read-only): counts vanilla world saves. UltraCore never cancels, delays or alters a save it did not issue.
 * Optional "managed autosave" (default OFF, see README): UltraCore turns the vanilla autosave off for a world and runs
 * world.save() itself:
 *   - one world at a time, at least stagger-seconds apart (no simultaneous multi-world write spikes);
 *   - deferred while the server is under pressure, but never longer than max-defer-seconds past the due time;
 *   - once right after the last player leaves a world (so idle worlds are clean on disk);
 *   - skipping empty worlds that were already saved while empty (optional);
 *   - a final save on reload/disable, then autosave is handed back to the server. If any save throws, autosave is
 *     immediately restored for that world.
 * Chunks are always written normally when they unload, regardless of this module.
 */
public final class ChunkSavingOptimizer extends AbstractModule implements Listener {
    private final Map<UUID, Boolean> originalAuto = new HashMap<>();
    private final Map<UUID, Long> lastSave = new HashMap<>();
    private final Set<UUID> savedWhileEmpty = new HashSet<>();
    private boolean managed, saveOnEmpty, skipEmpty, finalSave;
    private long intervalMs, staggerMs, maxDeferMs, lastAny, ownSaves, vanillaSaves, totalMs, maxMs, deferred;
    private int deferLevel;

    public ChunkSavingOptimizer(UltraCore p) { super(p, "chunksaving", "ChunkSavingOptimizer"); }

    @Override
    protected void onEnable() {
        managed = cfg.getBoolean("managed-autosave.enabled", false);
        intervalMs = Math.max(60, cfg.getInt("managed-autosave.interval-seconds", 300)) * 1000L;
        staggerMs = Math.max(1, cfg.getInt("managed-autosave.stagger-seconds", 20)) * 1000L;
        deferLevel = Math.max(1, Math.min(2, cfg.getInt("managed-autosave.defer-at-pressure-level", 2)));
        maxDeferMs = Math.max(0, cfg.getInt("managed-autosave.max-defer-seconds", 600)) * 1000L;
        saveOnEmpty = cfg.getBoolean("managed-autosave.save-when-world-empties", true);
        skipEmpty = cfg.getBoolean("managed-autosave.skip-unchanged-empty-worlds", false);
        finalSave = cfg.getBoolean("managed-autosave.final-save-on-disable", true);
        if (managed) {
            for (World w : Bukkit.getWorlds()) adopt(w);
            repeat(40L, 20L, this::tick);
            log("Managed autosave ACTIVE for " + originalAuto.size() + " world(s): UltraCore performs the saves.");
        }
    }

    private void adopt(World w) {
        if (plugin.isExcluded(w) || originalAuto.containsKey(w.getUID())) return;
        originalAuto.put(w.getUID(), w.isAutoSave());
        lastSave.put(w.getUID(), System.currentTimeMillis());
        w.setAutoSave(false);
    }

    private void release(World w) {
        Boolean orig = originalAuto.remove(w.getUID());
        lastSave.remove(w.getUID());
        savedWhileEmpty.remove(w.getUID());
        if (orig != null) w.setAutoSave(orig);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now - lastAny < staggerMs) return;
        World pick = null;
        long pickLast = Long.MAX_VALUE;
        for (UUID id : originalAuto.keySet()) {
            World w = Bukkit.getWorld(id);
            if (w == null) continue;
            if (w.getPlayerCount() > 0) savedWhileEmpty.remove(id);
            long last = lastSave.getOrDefault(id, 0L);
            if (now - last < intervalMs) continue;
            if (plugin.pressure() >= deferLevel && now - last < intervalMs + maxDeferMs) { deferred++; continue; }
            if (skipEmpty && w.getPlayerCount() == 0 && savedWhileEmpty.contains(id)) continue;
            if (last < pickLast) { pick = w; pickLast = last; }
        }
        if (pick != null) save(pick);
    }

    private void save(World w) {
        long t = System.nanoTime();
        try {
            w.save();
        } catch (Throwable ex) {
            warn("World save failed for " + w.getName() + " (" + ex + "); handing autosave back to the server for this world.");
            release(w);
            return;
        }
        long ms = (System.nanoTime() - t) / 1_000_000L;
        long now = System.currentTimeMillis();
        lastSave.put(w.getUID(), now);
        lastAny = now;
        ownSaves++; totalMs += ms; maxMs = Math.max(maxMs, ms);
        if (w.getPlayerCount() == 0) savedWhileEmpty.add(w.getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSave(WorldSaveEvent e) { vanillaSaves++; }

    @EventHandler
    public void onLoad(WorldLoadEvent e) { if (managed) adopt(e.getWorld()); }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) { if (managed) release(e.getWorld()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!managed || !saveOnEmpty) return;
        UUID wid = e.getPlayer().getWorld().getUID();
        Bukkit.getScheduler().runTask(plugin, () -> {
            World w = Bukkit.getWorld(wid);
            if (w != null && w.getPlayerCount() == 0 && lastSave.containsKey(wid)) lastSave.put(wid, 0L);
        });
    }

    @Override
    protected void onDisable() {
        for (UUID id : new HashMap<>(originalAuto).keySet()) {
            World w = Bukkit.getWorld(id);
            if (w == null) continue;
            if (finalSave) { try { w.save(); } catch (Throwable ex) { warn("Final save failed for " + w.getName() + ": " + ex); } }
            release(w);
        }
        originalAuto.clear(); lastSave.clear(); savedWhileEmpty.clear();
    }

    @Override
    public String optimizeNow() { return managed ? "managed autosave: " + ownSaves + " own saves so far" : "observing only (managed autosave is off)"; }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - ChunkSaving: vanilla world saves observed &f").append(vanillaSaves);
        if (managed) sb.append("&7, own saves &f").append(ownSaves).append("&7 (avg ").append(ownSaves == 0 ? 0 : totalMs / ownSaves).append(" ms, max ").append(maxMs).append(" ms), deferred checks &f").append(deferred);
        sb.append('\n');
    }
}
