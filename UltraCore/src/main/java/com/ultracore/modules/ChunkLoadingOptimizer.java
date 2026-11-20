package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Items 16 and 18: fewer chunk requests, and visibility into who causes unnecessary ones.
 *  - Adaptive world distances: under elevated/critical pressure the world's view and simulation distances are
 *    lowered step by step (never below the configured minimums) so fewer chunks are requested, loaded and ticked;
 *    they return to the admin's values when pressure clears and on reload/shutdown.
 *  - Fast-travel throttle: a player moving faster than a threshold (elytra, ice boats) has their view distance
 *    reduced while they travel (optionally only under pressure), which is what prevents chunk-generation storms.
 *    Restored when they slow down, quit or the plugin stops.
 *  - Orphan-load diagnostics: counts chunk loads that happen with no player nearby (caused by plugins, portals,
 *    redstone-loaded tickets...) and, with debug on, attributes them to the calling plugin's classes.
 * UltraCore itself never loads chunks: every scan only visits chunks that are already loaded.
 */
public final class ChunkLoadingOptimizer extends AbstractModule implements Listener {
    private final Map<UUID, int[]> base = new HashMap<>();
    private final Map<UUID, Integer> fast = new HashMap<>();
    private final Map<String, Integer> culprits = new HashMap<>();
    private boolean adaptive, fastEnabled, fastOnlyPressure, debug;
    private int minView, minSim, viewStep1, viewStep2, fastView;
    private double fastSpeed;
    private long orphanLoads, playerLoads;

    public ChunkLoadingOptimizer(UltraCore p) { super(p, "chunkloading", "ChunkLoadingOptimizer"); }

    @Override
    protected void onEnable() {
        adaptive = cfg.getBoolean("adaptive-distance.enabled", true);
        if (adaptive && !Compat.worldDistances) { warn("World#setViewDistance/setSimulationDistance missing; adaptive distances off."); adaptive = false; }
        minView = Math.max(2, cfg.getInt("adaptive-distance.min-view-distance", 5));
        minSim = Math.max(2, cfg.getInt("adaptive-distance.min-simulation-distance", 3));
        viewStep1 = Math.max(0, cfg.getInt("adaptive-distance.elevated-view-reduction", 2));
        viewStep2 = Math.max(viewStep1, cfg.getInt("adaptive-distance.critical-view-reduction", 4));
        fastEnabled = cfg.getBoolean("fast-travel.enabled", true);
        if (fastEnabled && !Compat.playerViewDistance) { warn("Player#setViewDistance missing; fast-travel throttle off."); fastEnabled = false; }
        fastSpeed = cfg.getDouble("fast-travel.speed-blocks-per-second", 25);
        fastView = Math.max(2, cfg.getInt("fast-travel.view-distance", 5));
        fastOnlyPressure = cfg.getBoolean("fast-travel.only-under-pressure", true);
        debug = cfg.getBoolean("debug-orphan-loads", false);
        if (adaptive) applyDistances(plugin.pressure());
        if (fastEnabled) repeat(40L, 40L, this::fastTick);
    }

    private void applyDistances(int level) {
        for (World w : Bukkit.getWorlds()) {
            if (plugin.isExcluded(w)) continue;
            int[] b = base.computeIfAbsent(w.getUID(), k -> new int[]{w.getViewDistance(), w.getSimulationDistance()});
            int vr = level == 0 ? 0 : level == 1 ? viewStep1 : viewStep2;
            int sr = level == 0 ? 0 : level == 1 ? 1 : 2;
            int view = Math.min(b[0], Math.max(minView, b[0] - vr));
            int sim = Math.min(b[1], Math.max(minSim, b[1] - sr));
            if (w.getViewDistance() != view) w.setViewDistance(view);
            if (w.getSimulationDistance() != sim) w.setSimulationDistance(sim);
        }
    }

    @Override
    public void onPressureChange(int oldLevel, int newLevel) { if (adaptive) applyDistances(newLevel); }

    private void fastTick() {
        PlayerMotion motion = plugin.motion();
        boolean pressure = plugin.pressure() >= 1;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerMotion.Entry e = motion.get(p.getUniqueId());
            if (e == null) continue;
            Integer orig = fast.get(p.getUniqueId());
            if (orig == null) {
                if (e.speed > fastSpeed && (!fastOnlyPressure || pressure)) {
                    int cur = p.getViewDistance();
                    if (cur > fastView) { fast.put(p.getUniqueId(), cur); p.setViewDistance(fastView); }
                }
            } else if (e.speed < fastSpeed * 0.5) {
                p.setViewDistance(p.getWorld().getViewDistance());
                fast.remove(p.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        double x = e.getChunk().getX() * 16.0 + 8, z = e.getChunk().getZ() * 16.0 + 8;
        if (plugin.motion().nearAnyPlayer(w.getUID(), x, z, (w.getViewDistance() + 2) * 16.0)) { playerLoads++; return; }
        orphanLoads++;
        if (debug) {
            String who = StackWalker.getInstance().walk(s -> s.map(StackWalker.StackFrame::getClassName)
                    .filter(n -> !(n.startsWith("net.minecraft") || n.startsWith("org.bukkit") || n.startsWith("io.papermc") || n.startsWith("java.")
                            || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("org.spigotmc") || n.startsWith("ca.spottedleaf")
                            || n.startsWith("com.mojang") || n.startsWith("com.ultracore") || n.startsWith("it.unimi") || n.startsWith("co.aikar")))
                    .findFirst().orElse("(server internals: tickets/portals/spawn)"));
            if (culprits.size() < 64 || culprits.containsKey(who)) culprits.merge(who, 1, Integer::sum);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { fast.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) { base.remove(e.getWorld().getUID()); }

    @Override
    protected void onDisable() {
        for (Map.Entry<UUID, Integer> en : fast.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) p.setViewDistance(p.getWorld().getViewDistance());
        }
        fast.clear();
        for (Map.Entry<UUID, int[]> en : base.entrySet()) {
            World w = Bukkit.getWorld(en.getKey());
            if (w == null) continue;
            w.setViewDistance(en.getValue()[0]);
            w.setSimulationDistance(en.getValue()[1]);
        }
        base.clear();
        culprits.clear();
    }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - ChunkLoading: loads near players &f").append(playerLoads).append("&7, loads with no player nearby &f").append(orphanLoads)
                .append("&7, fast travellers throttled now &f").append(fast.size()).append('\n');
        if (!culprits.isEmpty()) {
            sb.append("&7   orphan-load sources (debug): ");
            culprits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                    .forEach(en -> sb.append("&f").append(en.getKey()).append("&7=").append(en.getValue()).append(' '));
            sb.append('\n');
        }
    }
}
