package dev.euditor;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings stored in config/euditor.properties. */
public final class EuditorConfig {
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
			for (Module m : Module.values()) {
				m.set(Boolean.parseBoolean(p.getProperty(m.name(), Boolean.toString(m.defaultValue))));
			}
		} catch (Exception e) {
			System.err.println("[Euditor] Could not read config: " + e);
		}
	}

	public static void save() {
		try (OutputStream out = Files.newOutputStream(file())) {
			Properties p = new Properties();
			for (Module m : Module.values()) {
				p.setProperty(m.name(), Boolean.toString(m.on()));
			}
			p.store(out, "Euditor settings");
		} catch (Exception e) {
			System.err.println("[Euditor] Could not save config: " + e);
		}
	}
}
