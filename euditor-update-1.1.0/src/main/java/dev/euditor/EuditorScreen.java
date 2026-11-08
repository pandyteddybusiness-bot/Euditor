package dev.euditor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Euditor menu (Right Shift by default). */
public class EuditorScreen extends Screen {
	private static final int W = 150;
	private static final int H = 20;
	private static final int GAP = 24;
	private static final int COL_GAP = 10;

	private static final Module[] HUD = {
			Module.WATERMARK, Module.FPS, Module.COORDS, Module.FACING, Module.CPS,
			Module.KEYSTROKES, Module.RAM, Module.CLOCK, Module.BACKGROUND
	};
	private static final Module[] GAMEPLAY = {
			Module.ZOOM, Module.AUTO_SPRINT, Module.ENTITY_CULLING
	};

	public EuditorScreen() {
		super(Component.literal("Euditor"));
	}

	@Override
	protected void init() {
		int left = this.width / 2 - W - COL_GAP / 2;
		int right = this.width / 2 + COL_GAP / 2;
		int top = this.height / 2 - 125;

		Button brand = Button.builder(
				Component.literal("EUDITOR").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
				b -> {})
				.bounds(this.width / 2 - W - COL_GAP / 2, top, W * 2 + COL_GAP, H).build();
		brand.active = false;
		this.addRenderableWidget(brand);

		int y = top + GAP + 6;
		this.addRenderableWidget(header("HUD", left, y));
		this.addRenderableWidget(header("Gameplay & performance", right, y));
		y += GAP;

		int yl = y;
		for (Module m : HUD) {
			addToggle(m, left, yl);
			yl += GAP;
		}
		int yr = y;
		for (Module m : GAMEPLAY) {
			addToggle(m, right, yr);
			yr += GAP;
		}

		int bottom = Math.max(yl, yr) + 6;
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(this.width / 2 - 75, bottom, 150, H).build());
	}

	private Button header(String text, int x, int y) {
		Button b = Button.builder(Component.literal(text).withStyle(ChatFormatting.GRAY), btn -> {})
				.bounds(x, y, W, H).build();
		b.active = false;
		return b;
	}

	private void addToggle(Module m, int x, int y) {
		this.addRenderableWidget(Button.builder(label(m), b -> {
			m.toggle();
			b.setMessage(label(m));
			EuditorConfig.save();
		}).bounds(x, y, W, H).build());
	}

	private static Component label(Module m) {
		return Component.literal(m.label + ": " + (m.on() ? "ON" : "OFF"));
	}
}
