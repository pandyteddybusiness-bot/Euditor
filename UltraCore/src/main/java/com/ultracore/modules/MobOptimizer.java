package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.ChunkVisitor;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.EntityRules;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Items 7 and 8: mob AI and spawning.
 *  - AI: mobs with no player within ai-radius blocks get Mob#setAware(false) (Paper): their brain/goal/pathfinding
 *    work is skipped, but they still fall, burn and get pushed. They are re-enabled as soon as a player comes
 *    near. Villagers, named, leashed, tamed, ridden, raid and foreign-tagged mobs are never touched. Disabled mobs
 *    carry a "uc_unaware" tag so everything is restored on reload/shutdown even if awareness were persisted.
 *  - Spawning: natural spawns (optionally spawners) are cancelled when the chunk already holds too many mobs of
 *    that class or the spawn is farther than max-spawn-distance from every player. Caps shrink under pressure.
 *    Breeding, eggs, commands, plugins and structures are never limited.
 */
public final class MobOptimizer extends AbstractModule implements ChunkVisitor, Listener {
    private boolean awareness, spawnLimit, includeSpawners;
    private double aiRadius, maxSpawnDistance;
    private int maxHostile, maxPassive;
    private final Set<EntityType> awareExcluded = EnumSet.noneOf(EntityType.class);
    private long disabledNow, spawnsBlocked;

    public MobOptimizer(UltraCore p) { super(p, "mob", "MobOptimizer"); }

    @Override
    protected void onEnable() {
        awareness = cfg.getBoolean("ai-awareness.enabled", true);
        if (awareness && !Compat.mobAware) { warn("Mob#setAware missing; AI throttling off."); awareness = false; }
        aiRadius = cfg.getDouble("ai-awareness.radius", 48);
        awareExcluded.clear();
        for (String s : cfg.getStringList("ai-awareness.excluded-types")) {
            try { awareExcluded.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ex) { warn("Unknown entity type: " + s); }
        }
        spawnLimit = cfg.getBoolean("spawn-limit.enabled", true);
        includeSpawners = cfg.getBoolean("spawn-limit.include-spawners", false);
        maxHostile = cfg.getInt("spawn-limit.max-hostile-per-chunk", 10);
        maxPassive = cfg.getInt("spawn-limit.max-passive-per-chunk", 20);
        maxSpawnDistance = cfg.getDouble("spawn-limit.max-spawn-distance", 96);
        if (awareness) plugin.scanner().register(this);
    }

    private double scale() { int l = plugin.pressure(); return l == 0 ? 1.0 : l == 1 ? 0.75 : 0.5; }

    @Override public boolean needsEntities() { return true; }
    @Override public void cycleStart() { disabledNow = 0; }

    @Override
    public void visit(Chunk chunk, Entity[] ents) {
        if (ents.length == 0) return;
        PlayerMotion motion = plugin.motion();
        UUID wid = chunk.getWorld().getUID();
        double l = plugin.pressure();
        double radius = aiRadius * (l == 0 ? 1.0 : l == 1 ? 0.8 : 0.65);
        for (Entity e : ents) {
            if (!(e instanceof Mob m) || !m.isValid()) continue;
            boolean near = motion.nearAnyPlayer(wid, m.getX(), m.getZ(), radius);
            if (near) {
                if (!m.isAware() && m.getScoreboardTags().contains(EntityRules.TAG_UNAWARE)) {
                    m.setAware(true);
                    m.removeScoreboardTag(EntityRules.TAG_UNAWARE);
                }
            } else if (m.isAware() && !awareExcluded.contains(m.getType()) && !EntityRules.isProtected(m)) {
                m.setAware(false);
                m.addScoreboardTag(EntityRules.TAG_UNAWARE);
                disabledNow++;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!spawnLimit) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && !(includeSpawners && r == CreatureSpawnEvent.SpawnReason.SPAWNER)) return;
        Location loc = e.getLocation();
        World w = loc.getWorld();
        if (w == null || plugin.isExcluded(w)) return;
        if (maxSpawnDistance > 0 && !plugin.motion().nearAnyPlayer(w.getUID(), loc.getX(), loc.getZ(), maxSpawnDistance)) {
            e.setCancelled(true); spawnsBlocked++; return;
        }
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        if (!w.isChunkLoaded(cx, cz)) return; // never load a chunk just to count mobs
        boolean hostile = e.getEntity() instanceof Enemy;
        int cap = (int) Math.max(1, (hostile ? maxHostile : maxPassive) * scale());
        if ((hostile ? maxHostile : maxPassive) <= 0) return;
        int count = 0;
        for (Entity x : w.getChunkAt(cx, cz).getEntities()) {
            if (hostile ? x instanceof Enemy : x instanceof Animals) count++;
        }
        if (count >= cap) { e.setCancelled(true); spawnsBlocked++; }
    }

    @Override
    protected void onDisable() {
        if (!awareness) return;
        plugin.scanner().runNow(new ChunkVisitor() {
            @Override public boolean needsEntities() { return true; }
            @Override public void visit(Chunk c, Entity[] ents) {
                for (Entity e : ents) {
                    if (e instanceof Mob m && m.getScoreboardTags().contains(EntityRules.TAG_UNAWARE)) {
                        m.setAware(true);
                        m.removeScoreboardTag(EntityRules.TAG_UNAWARE);
                    }
                }
            }
        });
    }

    @Override
    public String optimizeNow() {
        if (awareness) plugin.scanner().runNow(this);
        return "AI paused on " + disabledNow + " far mob(s) this pass; natural spawns blocked so far: " + spawnsBlocked;
    }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - MobOptimizer: far mobs with AI paused (last cycle) &f").append(disabledNow).append("&7, spawns blocked &f").append(spawnsBlocked).append('\n');
    }
}
