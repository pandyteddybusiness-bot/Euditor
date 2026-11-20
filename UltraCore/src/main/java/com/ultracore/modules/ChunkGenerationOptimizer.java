package com.ultracore.modules;

import com.ultracore.UltraCore;
import com.ultracore.core.PlayerMotion;
import com.ultracore.module.AbstractModule;
import com.ultracore.util.Compat;
import com.ultracore.util.Keys;
import com.ultracore.util.LongLongMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Item 19: a safe, bounded chunk-generation scheduler.
 *
 * All generation requested THROUGH UltraCore (pregeneration jobs via /ultracore generation pregen, and the optional
 * player-direction prefetch) goes through one queue that:
 *  - uses only Paper's supported World#getChunkAtAsync (generation happens on Paper's own worker threads; no Bukkit
 *    API is ever called off the main thread: completion callbacks are re-dispatched to the main thread);
 *  - de-duplicates requests per world+chunk (queued or in flight), and drops chunks outside the world border;
 *  - has three priorities (PLAYER &gt; NORMAL &gt; PREGEN) and a hard queue-size limit;
 *  - limits simultaneous generations (lowered to 1 under elevated pressure) and how many are started per run;
 *  - pauses automatically when pressure reaches a configurable level (PREGEN pauses one level earlier) or when too
 *    many chunks are loaded; resumes on its own with hysteresis from the adaptive manager;
 *  - removes a player's pending requests on quit, and a world's on unload;
 *  - measures throughput: average ms per chunk, completions, failures, and new chunks generated per minute by ANY source.
 * Terrain correctness is untouched: chunks are produced by the normal world generator via the normal API.
 * Vanilla/Paper's own player-driven generation (walking around) is not rerouted, because Paper exposes no supported hook for
 * that; it is shaped indirectly by the Chunk Loading module (view distance, fast-travel throttle).
 */
public final class ChunkGenerationOptimizer extends AbstractModule implements Listener {
    public enum Priority { PLAYER, NORMAL, PREGEN }

    private static final class Request {
        final UUID world; final int x, z; final Priority pr; final UUID owner; long startNs;
        Request(UUID w, int x, int z, Priority p, UUID o) { world = w; this.x = x; this.z = z; pr = p; owner = o; }
    }

    private final ArrayDeque<Request> qPlayer = new ArrayDeque<>(), qNormal = new ArrayDeque<>(), qPregen = new ArrayDeque<>();
    private final Map<UUID, LongLongMap> pending = new HashMap<>();
    private int maxQueue, maxConcurrent, startsPerRun, pauseLevel, pregenPauseLevel, maxLoadedForPregen, maxPregenRadius, inFlight;
    private long completed, failed, rejected, duplicates, outsideBorder, newChunks, newPerMinute;
    private double emaMs;
    private boolean paused, autoPaused, stopped, prefetch;
    private int prefetchAhead;
    private double prefetchSpeed;

    // pregeneration job
    private UUID jobWorld;
    private int jobCx, jobCz, jobRadius, ring, ringIdx;
    private long jobTotal, jobEmitted, jobDone, jobStartMs;
    private boolean jobEmitting;

    public ChunkGenerationOptimizer(UltraCore p) { super(p, "chunkgeneration", "ChunkGenerationOptimizer"); }

    @Override
    protected void onEnable() {
        if (!Compat.asyncChunks) throw new UnsupportedOperationException("World#getChunkAtAsync(int,int,boolean,boolean) is missing (Paper required)");
        stopped = false;
        maxQueue = Math.max(16, cfg.getInt("max-queue-size", 512));
        maxConcurrent = Math.max(1, cfg.getInt("max-concurrent-generations", 2));
        startsPerRun = Math.max(1, cfg.getInt("max-starts-per-run", 1));
        int interval = Math.max(1, cfg.getInt("process-interval-ticks", 4));
        pauseLevel = Math.max(1, Math.min(2, cfg.getInt("pause-at-pressure-level", 2)));
        pregenPauseLevel = Math.max(1, Math.min(pauseLevel, cfg.getInt("pregen-pause-at-pressure-level", 1)));
        maxLoadedForPregen = Math.max(50, cfg.getInt("pregen-max-loaded-chunks", 600));
        maxPregenRadius = Math.max(1, cfg.getInt("max-pregen-radius-chunks", 256));
        prefetch = cfg.getBoolean("prefetch.enabled", false);
        prefetchAhead = Math.max(1, cfg.getInt("prefetch.chunks-ahead", 2));
        prefetchSpeed = cfg.getDouble("prefetch.min-speed-blocks-per-second", 6);
        repeat(40L, interval, this::process);
        repeat(1200L, 1200L, () -> { newPerMinute = newChunks; newChunks = 0; });
        if (prefetch) repeat(60L, 40L, this::prefetchTick);
    }

    // ---- public API ---------------------------------------------------------------------------------------------

    public int queueSize() { return qPlayer.size() + qNormal.size() + qPregen.size(); }
    public void setPaused(boolean p) { paused = p; }

    public boolean enqueue(World w, int x, int z, Priority pr, UUID owner) {
        if (stopped || plugin.isExcluded(w)) return false;
        if (outside(w, x, z)) { outsideBorder++; return false; }
        if (queueSize() >= maxQueue) { rejected++; return false; }
        LongLongMap m = pending.computeIfAbsent(w.getUID(), k -> new LongLongMap(64));
        long key = Keys.chunk(x, z);
        if (m.containsKey(key)) { duplicates++; return false; }
        m.put(key, 1);
        Request r = new Request(w.getUID(), x, z, pr, owner);
        (pr == Priority.PLAYER ? qPlayer : pr == Priority.NORMAL ? qNormal : qPregen).addLast(r);
        return true;
    }

    public String startPregen(World w, int cx, int cz, int radius) {
        if (jobWorld != null) return "&cA pregeneration job is already running (use /ultracore generation stop).";
        if (plugin.isExcluded(w)) return "&cThat world is excluded in settings.excluded-worlds.";
        radius = Math.max(0, Math.min(radius, maxPregenRadius));
        jobWorld = w.getUID(); jobCx = cx; jobCz = cz; jobRadius = radius;
        ring = 0; ringIdx = 0; jobEmitting = true; jobEmitted = 0; jobDone = 0; jobStartMs = System.currentTimeMillis();
        jobTotal = (2L * radius + 1) * (2L * radius + 1);
        return "&aPregeneration started in " + w.getName() + ": " + jobTotal + " chunks around chunk " + cx + "," + cz
                + " (radius " + radius + "). It auto-pauses under load; check progress with /ultracore generation.";
    }

    public String stopPregen() {
        if (jobWorld == null) return "&7No pregeneration job is running.";
        UUID jw = jobWorld;
        jobWorld = null; jobEmitting = false;
        qPregen.removeIf(r -> { forget(r); return true; });
        return "&eStopped. Chunks already generated are kept; " + jobDone + "/" + jobTotal + " were completed (world " + (Bukkit.getWorld(jw) == null ? "?" : Bukkit.getWorld(jw).getName()) + ").";
    }

    // ---- processing ---------------------------------------------------------------------------------------------

    private void process() {
        if (jobWorld != null && jobEmitting && qPregen.size() < 16) refill();
        if (jobWorld != null && !jobEmitting && queueSize() == 0 && inFlight == 0) finishJob();
        if (paused || queueSize() == 0) return;
        int lvl = plugin.pressure();
        autoPaused = lvl >= pauseLevel;
        if (autoPaused) return;
        int conc = lvl >= 1 ? 1 : maxConcurrent;
        int starts = 0;
        while (starts < startsPerRun && inFlight < conc) {
            Request r = next(lvl);
            if (r == null) break;
            start(r);
            starts++;
        }
    }

    private Request next(int lvl) {
        Request r = poll(qPlayer);
        if (r == null) r = poll(qNormal);
        if (r == null && lvl < pregenPauseLevel) {
            World jw = jobWorld == null ? null : Bukkit.getWorld(jobWorld);
            boolean tooMany = jw != null && Compat.chunkCount && jw.getChunkCount() > maxLoadedForPregen;
            if (!tooMany) r = poll(qPregen);
        }
        return r;
    }

    private Request poll(ArrayDeque<Request> q) {
        while (!q.isEmpty()) {
            Request r = q.pollFirst();
            if (Bukkit.getWorld(r.world) == null || (r.owner != null && Bukkit.getPlayer(r.owner) == null)) { forget(r); continue; }
            return r;
        }
        return null;
    }

    private void start(Request r) {
        World w = Bukkit.getWorld(r.world);
        r.startNs = System.nanoTime();
        inFlight++;
        try {
            w.getChunkAtAsync(r.x, r.z, true, r.pr == Priority.PLAYER).whenComplete((chunk, ex) -> {
                if (Bukkit.isPrimaryThread()) done(r, ex);
                else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done(r, ex));
            });
        } catch (Throwable t) {
            inFlight--; failed++; forget(r);
        }
    }

    private void done(Request r, Throwable ex) {
        if (stopped) return;
        inFlight = Math.max(0, inFlight - 1);
        forget(r);
        if (ex != null) { failed++; return; }
        completed++;
        if (r.pr == Priority.PREGEN) jobDone++;
        double ms = (System.nanoTime() - r.startNs) / 1_000_000.0;
        emaMs = emaMs == 0 ? ms : emaMs * 0.9 + ms * 0.1;
    }

    private void forget(Request r) {
        LongLongMap m = pending.get(r.world);
        if (m != null) m.remove(Keys.chunk(r.x, r.z));
    }

    // ---- pregeneration ------------------------------------------------------------------------------------------

    private void refill() {
        World w = Bukkit.getWorld(jobWorld);
        if (w == null) { stopPregen(); return; }
        int[] out = new int[2];
        int added = 0;
        while (added < 24 && jobEmitting && qPregen.size() < 32 && queueSize() < maxQueue) {
            if (!nextCoord(out)) { jobEmitting = false; break; }
            jobEmitted++;
            enqueue(w, jobCx + out[0], jobCz + out[1], Priority.PREGEN, null);
            added++;
        }
    }

    /** Square rings outward from the centre: ring 0 is the centre, ring r has 8r unique chunks. */
    private boolean nextCoord(int[] out) {
        if (ring > jobRadius) return false;
        if (ring == 0) { out[0] = 0; out[1] = 0; ring = 1; ringIdx = 0; return true; }
        int side = 2 * ring, t = ringIdx % side;
        switch (ringIdx / side) {
            case 0 -> { out[0] = -ring + t; out[1] = -ring; }
            case 1 -> { out[0] = ring; out[1] = -ring + t; }
            case 2 -> { out[0] = ring - t; out[1] = ring; }
            default -> { out[0] = -ring; out[1] = ring - t; }
        }
        if (++ringIdx >= 8 * ring) { ring++; ringIdx = 0; }
        return true;
    }

    private void finishJob() {
        long sec = Math.max(1, (System.currentTimeMillis() - jobStartMs) / 1000);
        log("Pregeneration finished: " + jobDone + " chunks in " + sec + " s (" + String.format("%.2f", jobDone / (double) sec) + " chunks/s).");
        jobWorld = null;
    }

    private boolean outside(World w, int cx, int cz) {
        WorldBorder b = w.getWorldBorder();
        Location c = b.getCenter();
        double half = b.getSize() / 2.0 + 16;
        return Math.abs(cx * 16.0 + 8 - c.getX()) > half || Math.abs(cz * 16.0 + 8 - c.getZ()) > half;
    }

    // ---- optional prefetch --------------------------------------------------------------------------------------

    private void prefetchTick() {
        if (plugin.pressure() > 0 || paused) return;
        PlayerMotion motion = plugin.motion();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerMotion.Entry e = motion.get(p.getUniqueId());
            if (e == null || e.speed < prefetchSpeed) continue;
            double len = Math.sqrt(e.dx * e.dx + e.dz * e.dz);
            if (len < 0.01) continue;
            double nx = e.dx / len, nz = e.dz / len;
            World w = p.getWorld();
            int vd = w.getViewDistance();
            for (int k = 1; k <= prefetchAhead; k++) {
                double dist = (vd + k) * 16.0;
                int cx = (int) Math.floor(e.x + nx * dist) >> 4, cz = (int) Math.floor(e.z + nz * dist) >> 4;
                enqueue(w, cx, cz, Priority.PLAYER, p.getUniqueId());
            }
        }
    }

    // ---- cleanup ------------------------------------------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (ArrayDeque<Request> q : new ArrayDeque[]{qPlayer, qNormal, qPregen}) {
            q.removeIf(r -> { if (id.equals(r.owner)) { forget(r); return true; } return false; });
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        UUID id = e.getWorld().getUID();
        for (ArrayDeque<Request> q : new ArrayDeque[]{qPlayer, qNormal, qPregen}) q.removeIf(r -> id.equals(r.world));
        pending.remove(id);
        if (id.equals(jobWorld)) { jobWorld = null; jobEmitting = false; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) { if (e.isNewChunk()) newChunks++; }

    @Override
    protected void onDisable() {
        stopped = true;
        qPlayer.clear(); qNormal.clear(); qPregen.clear();
        pending.clear();
        jobWorld = null; jobEmitting = false;
    }

    @Override
    public String optimizeNow() { return "queue holds " + queueSize() + " request(s), " + inFlight + " in flight"; }

    @Override
    public void describe(StringBuilder sb) {
        sb.append("&6Chunk generation:\n");
        sb.append("&7 - Queue: player &f").append(qPlayer.size()).append("&7, normal &f").append(qNormal.size()).append("&7, pregen &f").append(qPregen.size())
                .append("&7 (limit ").append(maxQueue).append("), in flight &f").append(inFlight).append("&7/").append(plugin.pressure() >= 1 ? 1 : maxConcurrent).append('\n');
        sb.append("&7 - State: ").append(paused ? "&epaused by command" : autoPaused ? "&eauto-paused (server under load)" : "&arunning").append('\n');
        sb.append(String.format("&7 - Completed &f%d&7, failed &f%d&7, duplicates blocked &f%d&7, rejected (queue full) &f%d&7, outside border &f%d\n", completed, failed, duplicates, rejected, outsideBorder));
        sb.append(String.format("&7 - Avg time per requested chunk &f%.1f ms&7; new chunks generated last minute (all sources) &f%d\n", emaMs, newPerMinute));
        if (jobWorld != null) sb.append("&7 - Pregen: &f").append(jobDone).append('/').append(jobTotal).append("&7 chunks done\n");
    }
}
