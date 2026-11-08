package dev.euditor;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple settings stored in config/euditor.properties. */
public final class EuditorConfig {
	public static boolean showFps = true;
	public static boolean showCoords = true;
	public static boolean showCps = true;
	public static boolean background = true;
	public static boolean zoom = true;

	private EuditorConfig() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("euditor.properties");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) {
			save();
			return;
		}
		try (InputStream in = Files.newInputStream(f)) {
			Properties p = new Properties();
			p.load(in);
			showFps = Boolean.parseBoolean(p.getProperty("showFps", "true"));
			showCoords = Boolean.parseBoolean(p.getProperty("showCoords", "true"));
			showCps = Boolean.parseBoolean(p.getProperty("showCps", "true"));
			background = Boolean.parseBoolean(p.getProperty("background", "true"));
			zoom = Boolean.parseBoolean(p.getProperty("zoom", "true"));
		} catch (Exception e) {
			System.err.println("[Euditor] Could not read config: " + e);
		}
	}

	public static void save() {
		try (OutputStream out = Files.newOutputStream(file())) {
			Properties p = new Properties();
			p.setProperty("showFps", Boolean.toString(showFps));
			p.setProperty("showCoords", Boolean.toString(showCoords));
			p.setProperty("showCps", Boolean.toString(showCps));
			p.setProperty("background", Boolean.toString(background));
			p.setProperty("zoom", Boolean.toString(zoom));
			p.store(out, "Euditor settings");
		} catch (Exception e) {
			System.err.println("[Euditor] Could not save config: " + e);
		}
	}
}
