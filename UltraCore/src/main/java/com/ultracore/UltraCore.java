package com.ultracore;

import com.ultracore.command.UltraCommand;
import com.ultracore.core.AdaptiveManager;
import com.ultracore.core.ChunkScanner;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.Module;
import com.ultracore.modules.*;
import com.ultracore.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class UltraCore extends JavaPlugin {
    private final List<Module> modules = new ArrayList<>();
    private final Set<String> excluded = new HashSet<>();
    private AdaptiveManager adaptive;
    private ChunkScanner scanner;
    private PlayerMotion motion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Compat.init();
        PluginCommand cmd = getCommand("ultracore");
        if (cmd != null) {
            UltraCommand uc = new UltraCommand(this);
            cmd.setExecutor(uc);
            cmd.setTabCompleter(uc);
        }
        start();
        getLogger().info("UltraCore started on " + Compat.serverName + " - " + activeCount() + "/" + modules.size() + " modules active.");
    }

    @Override
    public void onDisable() { stop(); }

    private void start() {
        excluded.clear();
        excluded.addAll(getConfig().getStringList("settings.excluded-worlds"));
        adaptive = new AdaptiveManager(this);
        scanner = new ChunkScanner(this);
        motion = new PlayerMotion(this);
        adaptive.start();
        motion.start();
        scanner.start();

        modules.clear();
        modules.add(new MemoryOptimizer(this));
        modules.add(new CpuOptimizer(this));
        modules.add(new GarbageCollectionOptimizer(this));
        modules.add(new EntityOptimizer(this));
        modules.add(new MobOptimizer(this));
        modules.add(new ItemOptimizer(this));
        modules.add(new HopperOptimizer(this));
        modules.add(new RedstoneOptimizer(this));
        modules.add(new BlockEntityOptimizer(this));
        modules.add(new RandomTickOptimizer(this));
        modules.add(new ChunkLoadingOptimizer(this));
        modules.add(new ChunkUnloadingOptimizer(this));
        modules.add(new ChunkGenerationOptimizer(this));
        modules.add(new ChunkSavingOptimizer(this));
        for (Module m : modules) m.enable();
    }

    private void stop() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            try { modules.get(i).disable(); } catch (Throwable t) { getLogger().log(Level.WARNING, "Module shutdown error", t); }
        }
        if (scanner != null) scanner.stop();
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
    }

    public void reload() {
        stop();
        reloadConfig();
        start();
    }

    public void dispatchPressure(int oldLevel, int newLevel) {
        for (Module m : modules) {
            if (!m.isActive()) continue;
            try { m.onPressureChange(oldLevel, newLevel); }
            catch (Throwable t) { getLogger().log(Level.WARNING, "Pressure handler failed in " + m.id(), t); }
        }
    }

    /** Runs every active module's one-shot pass. */
    public String runOptimizers() {
        StringBuilder sb = new StringBuilder();
        for (Module m : modules) {
            if (!m.isActive()) continue;
            try {
                String r = m.optimizeNow();
                if (r != null) sb.append("&7 - &f").append(m.displayName()).append("&7: ").append(r).append('\n');
            } catch (Throwable t) { sb.append("&7 - &c").append(m.displayName()).append(" failed: ").append(t.getClass().getSimpleName()).append('\n'); }
        }
        return sb.length() == 0 ? "&7Nothing to optimize." : sb.toString();
    }

    public int pressure() { return adaptive == null ? 0 : adaptive.level(); }
    public AdaptiveManager adaptive() { return adaptive; }
    public ChunkScanner scanner() { return scanner; }
    public PlayerMotion motion() { return motion; }
    public List<Module> modules() { return modules; }
    public boolean isExcluded(World w) { return excluded.contains(w.getName()); }

    public int activeCount() {
        int n = 0;
        for (Module m : modules) if (m.isActive()) n++;
        return n;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T module(Class<T> type) {
        for (Module m : modules) if (type.isInstance(m) && m.isActive()) return (T) m;
        return null;
    }

    public int generationQueueSize() {
        ChunkGenerationOptimizer g = module(ChunkGenerationOptimizer.class);
        return g == null ? 0 : g.queueSize();
    }
}
