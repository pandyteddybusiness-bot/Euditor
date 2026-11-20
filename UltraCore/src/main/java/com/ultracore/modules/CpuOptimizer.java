package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Item 2: cuts per-player simulation work. A player standing still (AFK) gets a reduced simulation distance, so
 * the chunks around them stop ticking entities/redstone/crops at full range; it is restored the moment they move.
 * Under elevated pressure the AFK threshold is halved. Only simulation distance is touched (view distance is
 * untouched), and everything is restored on quit, reload and shutdown.
 */
public final class CpuOptimizer extends AbstractModule implements Listener {
    private final Map<UUID, Integer> reduced = new HashMap<>();
    private int afkSeconds, afkSim;

    public CpuOptimizer(UltraCore p) { super(p, "cpu", "CpuOptimizer"); }

    @Override
    protected void onEnable() {
        if (!Compat.playerSimDistance) throw new UnsupportedOperationException("Player#setSimulationDistance is missing (Paper required)");
        afkSeconds = Math.max(10, cfg.getInt("afk-seconds", 120));
        afkSim = Math.max(2, Math.min(32, cfg.getInt("afk-simulation-distance", 3)));
        repeat(40L, 40L, this::tick);
    }

    private void tick() {
        int threshold = plugin.pressure() >= 1 ? Math.max(10, afkSeconds / 2) : afkSeconds;
        PlayerMotion motion = plugin.motion();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerMotion.Entry e = motion.get(p.getUniqueId());
            if (e == null) continue;
            boolean afk = e.stillSeconds >= threshold;
            Integer orig = reduced.get(p.getUniqueId());
            if (afk && orig == null) {
                int cur = p.getSimulationDistance();
                if (cur > afkSim) { reduced.put(p.getUniqueId(), cur); p.setSimulationDistance(afkSim); }
            } else if (!afk && orig != null) {
                p.setSimulationDistance(orig);
                reduced.remove(p.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { reduced.remove(e.getPlayer().getUniqueId()); }

    @Override
    protected void onDisable() {
        for (Map.Entry<UUID, Integer> en : reduced.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) p.setSimulationDistance(en.getValue());
        }
        reduced.clear();
    }

    @Override
    public String optimizeNow() { return reduced.size() + " AFK player(s) currently on reduced simulation distance"; }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - CpuOptimizer: AFK players on reduced simulation distance: &f").append(reduced.size()).append('\n');
    }
}
