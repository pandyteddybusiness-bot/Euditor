package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.module.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Item 15: random-tick load shedding through the vanilla randomTickSpeed game rule.
 * At normal pressure the rule is left exactly as the admin set it. Under elevated/critical pressure it is lowered
 * (default to 66% / 33% of the admin value, never below min-speed, default 1), which slows crop/sapling growth,
 * leaf decay, fire spread, etc. proportionally but keeps every mechanic working. It returns to the admin value as soon
 * as pressure clears, and on reload/shutdown. If an admin changes the rule meanwhile, the new value is adopted.
 */
public final class RandomTickOptimizer extends AbstractModule implements Listener {
    private final Map<UUID, int[]> state = new HashMap<>(); // [admin value, value we last set]
    private double f1, f2;
    private int min;

    public RandomTickOptimizer(UltraCore p) { super(p, "randomtick", "RandomTickOptimizer"); }

    @Override
    protected void onEnable() {
        f1 = Math.min(1.0, cfg.getDouble("elevated-percent", 66) / 100.0);
        f2 = Math.min(f1, cfg.getDouble("critical-percent", 33) / 100.0);
        min = Math.max(0, cfg.getInt("min-speed", 1));
        apply(plugin.pressure());
    }

    private void apply(int level) {
        for (World w : Bukkit.getWorlds()) {
            if (plugin.isExcluded(w)) continue;
            Integer cur = w.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
            if (cur == null) continue;
            int[] st = state.get(w.getUID());
            if (st == null) { st = new int[]{cur, cur}; state.put(w.getUID(), st); }
            else if (cur != st[1]) { st[0] = cur; st[1] = cur; } // admin changed it: adopt
            int target = st[0];
            if (level > 0) target = Math.min(st[0], Math.max(min, (int) Math.round(st[0] * (level == 1 ? f1 : f2))));
            if (target != cur) w.setGameRule(GameRule.RANDOM_TICK_SPEED, target);
            st[1] = target;
        }
    }

    @Override
    public void onPressureChange(int oldLevel, int newLevel) { apply(newLevel); }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) { state.remove(e.getWorld().getUID()); }

    @Override
    protected void onDisable() {
        for (Map.Entry<UUID, int[]> en : state.entrySet()) {
            World w = Bukkit.getWorld(en.getKey());
            if (w == null) continue;
            Integer cur = w.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
            if (cur != null && cur == en.getValue()[1]) w.setGameRule(GameRule.RANDOM_TICK_SPEED, en.getValue()[0]);
        }
        state.clear();
    }

    @Override
    public void describe(StringBuilder sb) {
        for (Map.Entry<UUID, int[]> en : state.entrySet()) {
            World w = Bukkit.getWorld(en.getKey());
            if (w != null) sb.append("&7 - RandomTick ").append(w.getName()).append(": admin &f").append(en.getValue()[0]).append("&7, effective &f").append(en.getValue()[1]).append('\n');
        }
    }
}
