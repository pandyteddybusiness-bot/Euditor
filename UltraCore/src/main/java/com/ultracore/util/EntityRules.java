package com.ultracore.util;

import org.bukkit.entity.*;

/** Shared "is this entity safe to touch/remove" rules. Conservative on purpose. */
public final class EntityRules {
    public static final String TAG_UNAWARE = "uc_unaware";
    private EntityRules() {}

    /** True if some other plugin/datapack put a scoreboard tag on it (we then leave it alone). */
    public static boolean hasForeignTags(Entity e) {
        var tags = e.getScoreboardTags();
        if (tags.isEmpty()) return false;
        for (String t : tags) if (!t.startsWith("uc_")) return true;
        return false;
    }

    public static boolean isProtected(LivingEntity e) {
        if (e instanceof Player || e instanceof Boss || e instanceof AbstractVillager || e instanceof Allay) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        if (e instanceof Mob m && m.isLeashed()) return true;
        if (e.customName() != null) return true;
        if (e.isInsideVehicle() || !e.getPassengers().isEmpty()) return true;
        if (e instanceof Raider r && r.getRaid() != null) return true;
        return hasForeignTags(e);
    }
}
