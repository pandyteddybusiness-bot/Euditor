package com.ultracore.command;

import com.ultracore.UltraCore;
import com.ultracore.core.AdaptiveManager;
import com.ultracore.module.Module;
import com.ultracore.modules.*;
import com.ultracore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;

import java.util.*;

public final class UltraCommand implements TabExecutor {
    private static final List<String> SUB = List.of("status", "reload", "optimize", "chunks", "memory", "entities", "generation");
    private static final List<String> GEN = List.of("status", "pregen", "stop", "pause", "resume");
    private final UltraCore plugin;

    public UltraCommand(UltraCore plugin) { this.plugin = plugin; }

    private boolean allowed(CommandSender s, String sub) {
        return s.hasPermission("ultracore.command") && s.hasPermission("ultracore." + sub);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (!SUB.contains(sub)) {
            Msg.send(s, "&cUsage: /" + label + " <" + String.join("|", SUB) + ">");
            return true;
        }
        if (!allowed(s, sub)) { Msg.send(s, "&cYou don't have permission for /ultracore " + sub + "."); return true; }
        StringBuilder sb = new StringBuilder();
        switch (sub) {
            case "status" -> status(sb);
            case "reload" -> { plugin.reload(); sb.append("&aUltraCore reloaded: ").append(plugin.activeCount()).append('/').append(plugin.modules().size()).append(" modules active."); }
            case "optimize" -> { sb.append("&6Optimization pass:\n").append(plugin.runOptimizers()); }
            case "chunks" -> chunks(sb);
            case "memory" -> memory(sb);
            case "entities" -> entities(sb);
            case "generation" -> generation(s, a, sb);
            default -> {}
        }
        for (String line : sb.toString().split("\n")) if (!line.isEmpty()) Msg.send(s, line);
        return true;
    }

    private void status(StringBuilder sb) {
        AdaptiveManager m = plugin.adaptive();
        m.sample();
        sb.append("&6&lUltraCore &7- pressure: &f").append(AdaptiveManager.levelName(plugin.pressure())).append('\n');
        sb.append(String.format("&7TPS: &f%.2f &7MSPT: &f%s&7\n", m.tps, m.mspt < 0 ? "n/a" : String.format("%.1f", m.mspt)));
        sb.append("&7RAM: &f").append(m.heapUsedMb).append(" MB used &7/ &f").append(m.heapCommittedMb).append(" MB committed &7/ &f").append(m.heapMaxMb).append(" MB max &7(old-gen ").append(String.format("%.0f%%", m.heapPct)).append(")\n");
        sb.append("&7Chunks: &f").append(m.chunks).append("  &7Entities: &f").append(m.entities).append("  &7Generation queue: &f").append(plugin.generationQueueSize()).append('\n');
        sb.append("&7Modules (&f").append(plugin.activeCount()).append("&7/").append(plugin.modules().size()).append("): ");
        for (Module mod : plugin.modules()) sb.append(mod.isActive() ? "&a" : "&8").append(mod.displayName()).append("&7, ");
        sb.append('\n');
    }

    private void chunks(StringBuilder sb) {
        sb.append("&6Chunks per world:\n");
        for (World w : Bukkit.getWorlds()) {
            sb.append("&7 - &f").append(w.getName()).append("&7: loaded &f").append(w.getChunkCount())
                    .append("&7, tickable block entities &f").append(w.getTickableTileEntityCount())
                    .append("&7, players &f").append(w.getPlayerCount())
                    .append("&7, view/sim &f").append(w.getViewDistance()).append('/').append(w.getSimulationDistance()).append('\n');
        }
        for (Module m : plugin.modules()) if (m.isActive() && (m instanceof ChunkLoadingOptimizer || m instanceof ChunkUnloadingOptimizer)) m.describe(sb);
    }

    private void memory(StringBuilder sb) {
        AdaptiveManager m = plugin.adaptive();
        m.sample();
        sb.append("&6Memory:\n");
        sb.append("&7 - Heap used &f").append(m.heapUsedMb).append(" MB&7, committed &f").append(m.heapCommittedMb).append(" MB&7, max &f").append(m.heapMaxMb).append(" MB\n");
        sb.append(String.format("&7 - Old-gen occupancy &f%.1f%%&7, GC overhead (last interval) &f%.1f%%\n", m.heapPct, m.gcOverheadPct));
        sb.append("&7 - Player tracker entries: &f").append(plugin.motion().tracked()).append('\n');
        for (Module mod : plugin.modules()) if (mod.isActive() && (mod instanceof MemoryOptimizer || mod instanceof GarbageCollectionOptimizer)) mod.describe(sb);
    }

    private void entities(StringBuilder sb) {
        sb.append("&6Entities:\n");
        for (World w : Bukkit.getWorlds()) {
            EnumMap<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
            for (Entity e : w.getEntities()) counts.merge(e.getType(), 1, Integer::sum);
            List<Map.Entry<EntityType, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort((x, y) -> y.getValue() - x.getValue());
            sb.append("&7 - &f").append(w.getName()).append("&7: &f").append(w.getEntityCount()).append("&7 total; top: ");
            for (int i = 0; i < Math.min(5, top.size()); i++) sb.append("&f").append(top.get(i).getKey().name().toLowerCase(Locale.ROOT)).append("&7=").append(top.get(i).getValue()).append(' ');
            sb.append('\n');
        }
        for (Module m : plugin.modules()) if (m.isActive() && (m instanceof EntityOptimizer || m instanceof MobOptimizer || m instanceof ItemOptimizer)) m.describe(sb);
    }

    private void generation(CommandSender s, String[] a, StringBuilder sb) {
        ChunkGenerationOptimizer g = plugin.module(ChunkGenerationOptimizer.class);
        if (g == null) { sb.append("&cThe ChunkGenerationOptimizer module is not active."); return; }
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (act) {
            case "pause" -> { g.setPaused(true); sb.append("&eGeneration queue paused."); }
            case "resume" -> { g.setPaused(false); sb.append("&aGeneration queue resumed."); }
            case "stop" -> sb.append(g.stopPregen());
            case "pregen" -> {
                World w = a.length > 3 ? Bukkit.getWorld(a[3]) : (s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
                int r;
                try { r = a.length > 2 ? Integer.parseInt(a[2]) : 32; } catch (NumberFormatException ex) { sb.append("&cRadius must be a number of chunks."); return; }
                if (w == null) { sb.append("&cUnknown world."); return; }
                int cx = 0, cz = 0;
                if (s instanceof Player p && p.getWorld().equals(w)) { cx = p.getLocation().getBlockX() >> 4; cz = p.getLocation().getBlockZ() >> 4; }
                else { cx = w.getSpawnLocation().getBlockX() >> 4; cz = w.getSpawnLocation().getBlockZ() >> 4; }
                sb.append(g.startPregen(w, cx, cz, r));
            }
            default -> g.describe(sb);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : SUB) if (x.startsWith(a[0].toLowerCase(Locale.ROOT)) && allowed(s, x)) out.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("generation")) {
            for (String x : GEN) if (x.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 4 && a[0].equalsIgnoreCase("generation") && a[1].equalsIgnoreCase("pregen")) {
            for (World w : Bukkit.getWorlds()) if (w.getName().toLowerCase(Locale.ROOT).startsWith(a[3].toLowerCase(Locale.ROOT))) out.add(w.getName());
        }
        return out;
    }
}
