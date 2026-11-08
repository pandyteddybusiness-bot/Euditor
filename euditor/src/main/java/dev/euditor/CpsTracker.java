package dev.euditor;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;

/** Counts left/right clicks per second by watching the attack/use keys. */
public final class CpsTracker {
	private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();
	private static boolean wasLeft;
	private static boolean wasRight;

	private CpsTracker() {}

	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();

		boolean left = mc.options.keyAttack.isDown();
		if (left && !wasLeft) LEFT.addLast(now);
		wasLeft = left;

		boolean right = mc.options.keyUse.isDown();
		if (right && !wasRight) RIGHT.addLast(now);
		wasRight = right;

		prune(LEFT, now);
		prune(RIGHT, now);
	}

	private static void prune(ArrayDeque<Long> q, long now) {
		while (!q.isEmpty() && now - q.peekFirst() > 1000) q.removeFirst();
	}

	public static int left() {
		return LEFT.size();
	}

	public static int right() {
		return RIGHT.size();
	}
}
