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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EuditorClient implements ClientModInitializer {
	public static final String MOD_ID = "euditor";
	private static final int ZOOM_FOV = 30; // lowest FOV vanilla allows

	private static KeyMapping zoomKey;
	private static KeyMapping menuKey;

	private static boolean zooming = false;
	private static int savedFov = 70;

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

	private static void onTick(Minecraft client) {
		if (client.player == null) {
			stopZoom(client);
			return;
		}

		while (menuKey.consumeClick()) {
			client.gui.setScreen(new EuditorScreen());
		}

		CpsTracker.tick(client);

		if (EuditorConfig.zoom && zoomKey.isDown()) {
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

	private static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		List<String> lines = new ArrayList<>();
		if (EuditorConfig.showFps) {
			lines.add("FPS: " + mc.getFps());
		}
		if (EuditorConfig.showCoords) {
			lines.add(String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f",
					mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		}
		if (EuditorConfig.showCps) {
			lines.add("CPS: " + CpsTracker.left() + " | " + CpsTracker.right());
		}
		if (lines.isEmpty()) return;

		int x = 4;
		int y = 4;
		int pad = 2;
		int lineHeight = 10;

		int width = 0;
		for (String line : lines) {
			width = Math.max(width, mc.font.width(line));
		}

		if (EuditorConfig.background) {
			g.fill(x - pad, y - pad, x + width + pad, y + lines.size() * lineHeight + pad - 1, 0x90000000);
		}
		for (int i = 0; i < lines.size(); i++) {
			g.text(mc.font, lines.get(i), x, y + i * lineHeight, 0xFFFFFFFF, true);
		}
	}
}
