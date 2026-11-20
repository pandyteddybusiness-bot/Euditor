package com.ultracore.module;

import com.ultracore.UltraCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public abstract class AbstractModule implements Module {
    protected final UltraCore plugin;
    protected ConfigurationSection cfg;
    private final String id;
    private final String name;
    private boolean active;

    protected AbstractModule(UltraCore plugin, String id, String name) {
        this.plugin = plugin;
        this.id = id;
        this.name = name;
    }

    @Override public final String id() { return id; }
    @Override public final String displayName() { return name; }
    @Override public final boolean isActive() { return active; }

    /** Reads settings into fields and starts tasks. Throw UnsupportedOperationException if a needed API is missing. */
    protected abstract void onEnable();
    protected void onDisable() {}

    @Override
    public final void enable() {
        cfg = plugin.getConfig().getConfigurationSection("modules." + id);
        if (cfg == null || !cfg.getBoolean("enabled", false)) return;
        try {
            onEnable();
            active = true;
            if (this instanceof Listener l) Bukkit.getPluginManager().registerEvents(l, plugin);
        } catch (UnsupportedOperationException e) {
            plugin.getLogger().info("[" + name + "] not available on this server: " + e.getMessage());
            safeDisable();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[" + name + "] failed to start and was disabled", t);
            safeDisable();
        }
    }

    @Override
    public final void disable() {
        if (!active) return;
        safeDisable();
    }

    private void safeDisable() {
        active = false;
        try { onDisable(); } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[" + name + "] error during shutdown", t);
        }
    }

    protected final BukkitTask repeat(long delay, long period, Runnable r) {
        return Bukkit.getScheduler().runTaskTimer(plugin, r, delay, Math.max(1L, period));
    }

    protected final void log(String s) { plugin.getLogger().info("[" + name + "] " + s); }
    protected final void warn(String s) { plugin.getLogger().warning("[" + name + "] " + s); }
}
