package dev.euditor;

/** Every toggleable Euditor feature. */
public enum Module {
	WATERMARK("Euditor watermark", true),
	FPS("FPS counter", true),
	COORDS("Coordinates", true),
	FACING("Facing direction", false),
	CPS("CPS counter", true),
	KEYSTROKES("Keystrokes", true),
	RAM("Memory usage", false),
	CLOCK("Clock", false),
	BACKGROUND("HUD background", true),
	ZOOM("Zoom", true),
	AUTO_SPRINT("Auto sprint", false),
	ENTITY_CULLING("Entity culling", true);

	public final String label;
	public final boolean defaultValue;
	private boolean enabled;

	Module(String label, boolean defaultValue) {
		this.label = label;
		this.defaultValue = defaultValue;
		this.enabled = defaultValue;
	}

	public boolean on() {
		return enabled;
	}

	public void set(boolean value) {
		this.enabled = value;
	}

	public void toggle() {
		this.enabled = !this.enabled;
	}
}
