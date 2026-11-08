package dev.euditor;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Euditor's built-in entity culling.
 *
 * Entities that are completely hidden behind blocks are not rendered. Visibility is checked
 * with a few line-of-sight rays from the camera and cached for ~100 ms per entity, with a
 * per-frame ray budget so it never costs more than a tiny slice of a frame.
 */
public final class EntityCulling {
	private static final class State {
		boolean hidden;
		long nextCheck;
		long lastSeen;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	private static final double NEVER_CULL_DISTANCE_SQ = 8.0 * 8.0;
	private static final double MAX_SIZE = 6.0;
	private static final int RAYS_PER_FRAME_BUDGET = 40;
	private static final int RECHECK_MS = 100;

	private static int budget = RAYS_PER_FRAME_BUDGET;
	private static long lastBudgetReset = 0;
	private static long lastCleanup = 0;

	private EntityCulling() {}

	/** Number of entities currently hidden by culling (shown on the HUD). */
	public static int hiddenCount() {
		int n = 0;
		for (State s : STATES.values()) {
			if (s.hidden) n++;
		}
		return n;
	}

	/** Returns true if this entity should NOT be rendered this frame. */
	public static boolean shouldCull(Entity entity, double camX, double camY, double camZ) {
		if (!Module.ENTITY_CULLING.on()) return false;

		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null || mc.player == null) return false;

		// Never hide the player, their vehicle/passengers, or glowing entities.
		if (entity == mc.player) return false;
		if (entity.getRootVehicle() == mc.player.getRootVehicle()) return false;
		if (entity.isCurrentlyGlowing()) return false;

		AABB box = entity.getBoundingBox();
		if (Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) > MAX_SIZE) return false;

		Vec3 center = box.getCenter();
		double dx = center.x - camX;
		double dy = center.y - camY;
		double dz = center.z - camZ;
		if (dx * dx + dy * dy + dz * dz < NEVER_CULL_DISTANCE_SQ) return false;

		long now = System.currentTimeMillis();
		if (now - lastBudgetReset >= 8) {
			lastBudgetReset = now;
			budget = RAYS_PER_FRAME_BUDGET;
		}
		if (now - lastCleanup > 5000) {
			lastCleanup = now;
			STATES.values().removeIf(s -> now - s.lastSeen > 5000);
		}

		State state = STATES.computeIfAbsent(entity.getId(), k -> new State());
		state.lastSeen = now;

		if (now >= state.nextCheck && budget > 0) {
			Vec3 cam = new Vec3(camX, camY, camZ);
			state.hidden = !isVisible(level, mc, cam, box, center);
			state.nextCheck = now + RECHECK_MS + (entity.getId() % 40);
			budget -= 3;
		}
		return state.hidden;
	}

	private static boolean isVisible(Level level, Minecraft mc, Vec3 cam, AABB box, Vec3 c) {
		double ox = box.getXsize() * 0.4;
		double oz = box.getZsize() * 0.4;

		if (clear(level, mc, cam, c)) return true;
		if (clear(level, mc, cam, new Vec3(c.x, box.maxY - 0.1, c.z))) return true;
		if (clear(level, mc, cam, new Vec3(c.x, box.minY + 0.1, c.z))) return true;
		if (clear(level, mc, cam, new Vec3(c.x + ox, c.y, c.z + oz))) return true;
		if (clear(level, mc, cam, new Vec3(c.x - ox, c.y, c.z - oz))) return true;
		if (clear(level, mc, cam, new Vec3(c.x + ox, c.y, c.z - oz))) return true;
		return clear(level, mc, cam, new Vec3(c.x - ox, c.y, c.z + oz));
	}

	private static boolean clear(Level level, Minecraft mc, Vec3 from, Vec3 to) {
		BlockHitResult hit = level.clip(new ClipContext(
				from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() == HitResult.Type.MISS) return true;
		// A hit right next to the target (entity standing in a slab, doorway, etc.) still counts as visible.
		return hit.getLocation().distanceToSqr(to) < 0.36;
	}
}
