package dev.euditor;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The Euditor settings menu (open it with Right Shift by default). */
public class EuditorScreen extends Screen {
	private static final int W = 200;
	private static final int H = 20;
	private static final int GAP = 24;

	public EuditorScreen() {
		super(Component.literal("Euditor"));
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		int y = this.height / 2 - 80;

		Button header = Button.builder(Component.literal("Euditor"), b -> {})
				.bounds(x, y, W, H).build();
		header.active = false;
		this.addRenderableWidget(header);
		y += GAP + 6;

		toggle(x, y, "FPS counter", () -> EuditorConfig.showFps, v -> EuditorConfig.showFps = v);
		y += GAP;
		toggle(x, y, "Coordinates", () -> EuditorConfig.showCoords, v -> EuditorConfig.showCoords = v);
		y += GAP;
		toggle(x, y, "CPS counter", () -> EuditorConfig.showCps, v -> EuditorConfig.showCps = v);
		y += GAP;
		toggle(x, y, "HUD background", () -> EuditorConfig.background, v -> EuditorConfig.background = v);
		y += GAP;
		toggle(x, y, "Zoom", () -> EuditorConfig.zoom, v -> EuditorConfig.zoom = v);
		y += GAP + 6;

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(x, y, W, H).build());
	}

	private void toggle(int x, int y, String name, BooleanSupplier get, Consumer<Boolean> set) {
		this.addRenderableWidget(Button.builder(label(name, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(label(name, get.getAsBoolean()));
			EuditorConfig.save();
		}).bounds(x, y, W, H).build());
	}

	private static Component label(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"));
	}
}
