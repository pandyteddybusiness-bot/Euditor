package dev.euditor;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EuditorClient implements ClientModInitializer {
	public static final String MOD_ID = "euditor";
	public static final String VERSION = "1.1.0";

	private static final int ACCENT = 0xFFB388FF;   // Euditor purple
	private static final int WHITE = 0xFFFFFFFF;
	private static final int ZOOM_FOV = 30;          // lowest FOV vanilla allows
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private static KeyMapping zoomKey;
	private static KeyMapping menuKey;

	private static boolean zooming = false;
	private static int savedFov = 70;

	private record Line(String text, int color) {}

	@Override
	public void onInitializeClient() {
		EuditorConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.euditor.zoom", InputConstants.Type.KEYSYM, InputConstants.KEY_C, category));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.euditor.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.HOTBAR,
				Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
				EuditorClient::extractHud);

		ClientTickEvents.END_CLIENT_TICK.register(EuditorClient::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(EuditorClient::stopZoom);
	}

	// ------------------------------------------------------------------ tick

	private static void onTick(Minecraft client) {
		if (client.player == null) {
			stopZoom(client);
			return;
		}

		while (menuKey.consumeClick()) {
			client.gui.setScreen(new EuditorScreen());
		}

		CpsTracker.tick(client);

		if (Module.AUTO_SPRINT.on() && client.options.keyUp.isDown()) {
			client.options.keySprint.setDown(true);
		}

		if (Module.ZOOM.on() && zoomKey.isDown()) {
			startZoom(client);
		} else {
			stopZoom(client);
		}
	}

	private static void startZoom(Minecraft client) {
		if (!zooming) {
			savedFov = client.options.fov().get();
			zooming = true;
		}
		client.options.fov().set(ZOOM_FOV);
	}

	private static void stopZoom(Minecraft client) {
		if (zooming) {
			client.options.fov().set(savedFov);
			zooming = false;
		}
	}

	// ------------------------------------------------------------------- HUD

	private static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		List<Line> lines = new ArrayList<>();

		if (Module.WATERMARK.on()) {
			lines.add(new Line("EUDITOR " + VERSION, ACCENT));
		}
		if (Module.FPS.on()) {
			String fps = "FPS: " + mc.getFps();
			if (Module.ENTITY_CULLING.on()) {
				fps += "  |  Culled: " + EntityCulling.hiddenCount();
			}
			lines.add(new Line(fps, WHITE));
		}
		if (Module.COORDS.on()) {
			lines.add(new Line(String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f",
					mc.player.getX(), mc.player.getY(), mc.player.getZ()), WHITE));
		}
		if (Module.FACING.on()) {
			String dir = mc.player.getDirection().toString();
			if (!dir.isEmpty()) dir = Character.toUpperCase(dir.charAt(0)) + dir.substring(1);
			lines.add(new Line("Facing: " + dir, WHITE));
		}
		if (Module.CPS.on()) {
			lines.add(new Line("CPS: " + CpsTracker.left() + " | " + CpsTracker.right(), WHITE));
		}
		if (Module.RAM.on()) {
			Runtime rt = Runtime.getRuntime();
			long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
			long max = rt.maxMemory() / (1024 * 1024);
			lines.add(new Line("RAM: " + used + " / " + max + " MB", WHITE));
		}
		if (Module.CLOCK.on()) {
			lines.add(new Line("Time: " + LocalTime.now().format(CLOCK), WHITE));
		}

		int x = 4;
		int y = 4;
		int pad = 2;
		int lineHeight = 10;

		if (!lines.isEmpty()) {
			int width = 0;
			for (Line line : lines) {
				width = Math.max(width, mc.font.width(line.text()));
			}
			if (Module.BACKGROUND.on()) {
				g.fill(x - pad, y - pad, x + width + pad, y + lines.size() * lineHeight + pad - 1, 0x90000000);
				// accent bar on the left edge = Euditor branding
				g.fill(x - pad - 2, y - pad, x - pad, y + lines.size() * lineHeight + pad - 1, ACCENT);
			}
			for (int i = 0; i < lines.size(); i++) {
				Line line = lines.get(i);
				g.text(mc.font, line.text(), x, y + i * lineHeight, line.color(), true);
			}
			y += lines.size() * lineHeight + pad + 6;
		}

		if (Module.KEYSTROKES.on()) {
			drawKeystrokes(g, mc, x, y);
		}
	}

	private static void drawKeystrokes(GuiGraphicsExtractor g, Minecraft mc, int x, int y) {
		int s = 20;
		int gap = 2;

		key(g, mc, "W", x + s + gap, y, s, s, mc.options.keyUp.isDown());
		key(g, mc, "A", x, y + s + gap, s, s, mc.options.keyLeft.isDown());
		key(g, mc, "S", x + s + gap, y + s + gap, s, s, mc.options.keyDown.isDown());
		key(g, mc, "D", x + (s + gap) * 2, y + s + gap, s, s, mc.options.keyRight.isDown());

		int wide = (s * 3 + gap * 2 - gap) / 2;
		key(g, mc, "LMB", x, y + (s + gap) * 2, wide, 16, mc.options.keyAttack.isDown());
		key(g, mc, "RMB", x + wide + gap, y + (s + gap) * 2, wide, 16, mc.options.keyUse.isDown());
	}

	private static void key(GuiGraphicsExtractor g, Minecraft mc, String label,
							int x, int y, int w, int h, boolean down) {
		g.fill(x, y, x + w, y + h, down ? 0xC0B388FF : 0x90000000);
		int tx = x + (w - mc.font.width(label)) / 2;
		int ty = y + (h - 8) / 2;
		g.text(mc.font, label, tx, ty, down ? 0xFF000000 : WHITE, false);
	}
}
