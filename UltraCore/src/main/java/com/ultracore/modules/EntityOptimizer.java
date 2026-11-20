package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.ChunkVisitor;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.EntityRules;
import org.bukkit.Chunk;
import org.bukkit.entity.*;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Items 6 and 9 (entity side): bounded per-chunk entity retention.
 *  - Far despawn: despawnable mobs (getRemoveWhenFarAway) with no player within a configurable distance are removed
 *    sooner than vanilla's random despawn roll. Persistent mobs are never touched.
 *  - Per-chunk caps for hostile mobs and animals; the excess is removed. Anything named, leashed, tamed, ridden,
 *    in a raid, tagged by another plugin, a villager, a boss or in the exclusion list is never removed.
 * The caps scale down automatically under elevated/critical pressure.
 * Runs inside the shared chunk scanner (bounded chunks per tick), never as a full-world sweep.
 */
public final class EntityOptimizer extends AbstractModule implements ChunkVisitor {
    private int maxHostile, maxAnimals;
    private double farDespawn;
    private final Set<EntityType> excluded = EnumSet.noneOf(EntityType.class);
    private long removedFar, removedCap;

    public EntityOptimizer(UltraCore p) { super(p, "entity", "EntityOptimizer"); }

    @Override
    protected void onEnable() {
        maxHostile = cfg.getInt("max-hostile-per-chunk", 24);
        maxAnimals = cfg.getInt("max-animals-per-chunk", 48);
        farDespawn = cfg.getDouble("far-despawn-distance", 96);
        excluded.clear();
        for (String s : cfg.getStringList("excluded-types")) {
            try { excluded.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ex) { warn("Unknown entity type in excluded-types: " + s); }
        }
        plugin.scanner().register(this);
    }

    @Override public boolean needsEntities() { return true; }

    private double scale() { int l = plugin.pressure(); return l == 0 ? 1.0 : l == 1 ? 0.75 : 0.5; }

    private boolean removable(LivingEntity e) { return !excluded.contains(e.getType()) && !EntityRules.isProtected(e); }

    @Override
    public void visit(Chunk chunk, Entity[] ents) {
        if (ents.length == 0) return;
        PlayerMotion motion = plugin.motion();
        UUID wid = chunk.getWorld().getUID();
        int hostile = 0, animals = 0;
        for (Entity e : ents) {
            if (e instanceof Enemy) hostile++;
            else if (e instanceof Animals) animals++;
        }
        if (farDespawn > 0) {
            for (Entity e : ents) {
                if (!(e instanceof Mob m) || !m.isValid() || !m.getRemoveWhenFarAway() || !removable(m)) continue;
                if (!motion.nearAnyPlayer(wid, m.getX(), m.getZ(), farDespawn)) {
                    if (m instanceof Enemy) hostile--; else if (m instanceof Animals) animals--;
                    m.remove();
                    removedFar++;
                }
            }
        }
        double s = scale();
        if (maxHostile > 0 && hostile > maxHostile * s) {
            int cap = (int) Math.max(1, maxHostile * s);
            for (Entity e : ents) {
                if (hostile <= cap) break;
                if (e instanceof Enemy && e instanceof Mob m && m.isValid() && m.getRemoveWhenFarAway() && removable(m)) { m.remove(); hostile--; removedCap++; }
            }
        }
        if (maxAnimals > 0 && animals > maxAnimals * s) {
            int cap = (int) Math.max(1, maxAnimals * s);
            for (Entity e : ents) {
                if (animals <= cap) break;
                if (e instanceof Animals a && a.isValid() && removable(a)) { a.remove(); animals--; removedCap++; }
            }
        }
    }

    @Override
    public String optimizeNow() {
        long before = removedFar + removedCap;
        plugin.scanner().runNow(this);
        return "removed " + (removedFar + removedCap - before) + " excess/far mobs";
    }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - EntityOptimizer: removed far-despawn &f").append(removedFar).append("&7, over-cap &f").append(removedCap).append('\n');
    }
}
