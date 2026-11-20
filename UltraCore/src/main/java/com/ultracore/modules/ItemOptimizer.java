package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.ChunkVisitor;
import com.ultracore.module.AbstractModule;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Items 10 and 11: dropped item processing.
 *  - Merge: nearby identical stacks inside a chunk are merged up to max stack size using a configurable radius
 *    (larger than vanilla's), keeping the lower age and higher pickup delay like vanilla does. Bounded work:
 *    at most merge-scan-limit items are compared per chunk (O(n^2) on a bounded n).
 *  - Cleanup: per-chunk item limit (oldest removed first) and an optional maximum age. Valuable items (configured
 *    materials, shulker boxes, named and enchanted items by default) are never removed. Limits shrink under pressure.
 */
public final class ItemOptimizer extends AbstractModule implements ChunkVisitor {
    private boolean merge, cleanup, protectNamed, protectEnchanted;
    private double radiusSq;
    private int scanLimit, maxPerChunk, maxAgeTicks;
    private final Set<Material> protectedMats = EnumSet.noneOf(Material.class);
    private final ArrayList<Item> buf = new ArrayList<>(128);
    private long merged, removed;

    public ItemOptimizer(UltraCore p) { super(p, "item", "ItemOptimizer"); }

    @Override
    protected void onEnable() {
        merge = cfg.getBoolean("merge.enabled", true);
        double r = cfg.getDouble("merge.radius", 3.0);
        radiusSq = r * r;
        scanLimit = Math.max(2, cfg.getInt("merge.scan-limit-per-chunk", 64));
        cleanup = cfg.getBoolean("cleanup.enabled", true);
        maxPerChunk = Math.max(16, cfg.getInt("cleanup.max-items-per-chunk", 96));
        maxAgeTicks = Math.max(0, cfg.getInt("cleanup.max-age-seconds", 0)) * 20;
        protectNamed = cfg.getBoolean("cleanup.protect-named", true);
        protectEnchanted = cfg.getBoolean("cleanup.protect-enchanted", true);
        protectedMats.clear();
        for (String s : cfg.getStringList("cleanup.protected-materials")) {
            Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
            if (m != null) protectedMats.add(m); else warn("Unknown material in protected-materials: " + s);
        }
        plugin.scanner().register(this);
    }

    @Override public boolean needsEntities() { return true; }

    @Override
    public void visit(Chunk chunk, Entity[] ents) {
        buf.clear();
        for (Entity e : ents) if (e instanceof Item it && it.isValid()) buf.add(it);
        if (buf.isEmpty()) return;
        if (merge && buf.size() > 1) mergePass();
        if (cleanup) cleanPass();
        buf.clear();
    }

    private static boolean skip(Item it) { return it.getPickupDelay() >= 32767 || it.customName() != null; }

    private void mergePass() {
        int n = Math.min(buf.size(), scanLimit);
        for (int i = 0; i < n; i++) {
            Item a = buf.get(i);
            if (!a.isValid() || skip(a)) continue;
            ItemStack sa = a.getItemStack();
            int max = sa.getMaxStackSize();
            if (sa.getAmount() >= max) continue;
            for (int j = i + 1; j < n; j++) {
                Item b = buf.get(j);
                if (!b.isValid() || skip(b)) continue;
                double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
                if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                if (!Objects.equals(a.getOwner(), b.getOwner()) || !Objects.equals(a.getThrower(), b.getThrower())) continue;
                ItemStack sb = b.getItemStack();
                if (!sa.isSimilar(sb)) continue;
                int total = sa.getAmount() + sb.getAmount();
                a.setTicksLived(Math.max(1, Math.min(a.getTicksLived(), b.getTicksLived())));
                a.setPickupDelay(Math.max(a.getPickupDelay(), b.getPickupDelay()));
                if (total <= max) {
                    sa.setAmount(total);
                    a.setItemStack(sa);
                    b.remove();
                    merged++;
                } else {
                    int move = max - sa.getAmount();
                    sa.setAmount(max);
                    a.setItemStack(sa);
                    sb.setAmount(sb.getAmount() - move);
                    b.setItemStack(sb);
                    break;
                }
            }
        }
    }

    private boolean isProtected(Item it) {
        ItemStack st = it.getItemStack();
        Material m = st.getType();
        if (protectedMats.contains(m) || m.name().endsWith("SHULKER_BOX")) return true;
        if ((protectNamed || protectEnchanted) && st.hasItemMeta()) {
            ItemMeta im = st.getItemMeta();
            if (protectNamed && im.hasDisplayName()) return true;
            if (protectEnchanted && im.hasEnchants()) return true;
        }
        return false;
    }

    private void cleanPass() {
        int l = plugin.pressure();
        int cap = Math.max(16, (int) (maxPerChunk * (l == 0 ? 1.0 : l == 1 ? 0.5 : 0.25)));
        int live = 0;
        for (int i = 0; i < buf.size(); i++) {
            Item it = buf.get(i);
            if (!it.isValid()) continue;
            if (maxAgeTicks > 0 && it.getTicksLived() > maxAgeTicks && !isProtected(it)) { it.remove(); removed++; continue; }
            live++;
        }
        if (live <= cap) return;
        ArrayList<Item> cand = new ArrayList<>(live);
        for (int i = 0; i < buf.size(); i++) {
            Item it = buf.get(i);
            if (it.isValid() && !isProtected(it)) cand.add(it);
        }
        cand.sort((x, y) -> Integer.compare(y.getTicksLived(), x.getTicksLived())); // oldest first
        for (int i = 0; i < cand.size() && live > cap; i++) { cand.get(i).remove(); live--; removed++; }
    }

    @Override
    public String optimizeNow() {
        long m = merged, r = removed;
        plugin.scanner().runNow(this);
        return "merged " + (merged - m) + " stacks, removed " + (removed - r) + " excess items";
    }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&7 - ItemOptimizer: stacks merged &f").append(merged).append("&7, items removed &f").append(removed).append('\n');
    }
}
