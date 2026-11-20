package com.ultracore.core;

import com.ultracore.UltraCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.UUID;

/**
 * One 1-second task that records where every player is and how fast they move. Shared by the modules that need
 * "is any player near here?", AFK detection or fast-travel detection, instead of each module polling on its own.
 * Entries are removed on quit, so it cannot leak.
 */
public final class PlayerMotion implements Listener {
    public static final class Entry {
        public UUID world;
        public double x, z, dx, dz, speed;
        public int stillSeconds;
        boolean init;
    }

    private final UltraCore plugin;
    private final HashMap<UUID, Entry> map = new HashMap<>();

    public PlayerMotion(UltraCore plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 20L);
    }

    private void update() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Entry e = map.computeIfAbsent(p.getUniqueId(), k -> new Entry());
            UUID w = p.getWorld().getUID();
            double x = p.getX(), z = p.getZ();
            if (!e.init || !w.equals(e.world)) {
                e.init = true; e.world = w; e.speed = 0; e.dx = 0; e.dz = 0; e.stillSeconds = 0;
            } else {
                double dx = x - e.x, dz = z - e.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 200) { // teleport, not travel
                    e.speed = 0; e.dx = 0; e.dz = 0; e.stillSeconds = 0;
                } else {
                    e.dx = dx; e.dz = dz; e.speed = d;
                    if (d < 0.5) e.stillSeconds++; else e.stillSeconds = 0;
                }
            }
            e.x = x; e.z = z;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { map.remove(e.getPlayer().getUniqueId()); }

    public Entry get(UUID id) { return map.get(id); }

    /** Horizontal distance check against the last-known (≤1 s old) position of every player in the world. */
    public boolean nearAnyPlayer(UUID world, double x, double z, double dist) {
        double d2 = dist * dist;
        for (Entry e : map.values()) {
            if (!world.equals(e.world)) continue;
            double dx = e.x - x, dz = e.z - z;
            if (dx * dx + dz * dz <= d2) return true;
        }
        return false;
    }

    public int tracked() { return map.size(); }

    /** Defensive leak guard: drop entries for players that are no longer online. */
    public int prune() {
        int before = map.size();
        map.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        return before - map.size();
    }
}
