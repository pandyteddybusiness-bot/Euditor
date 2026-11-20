package com.ultracore.util;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Runtime feature detection so a missing API disables one module instead of crashing the server. */
public final class Compat {
    public static boolean asyncChunks, mobAware, playerViewDistance, playerSimDistance, worldDistances,
            unloadRequest, tps, avgTickTime, entitiesLoaded, pluginTickets, chunkCount, currentTick;
    public static String serverName = "unknown";

    private Compat() {}

    public static void init() {
        serverName = Bukkit.getName() + " " + Bukkit.getMinecraftVersion();
        asyncChunks = has(World.class, "getChunkAtAsync", int.class, int.class, boolean.class, boolean.class);
        mobAware = has(Mob.class, "setAware", boolean.class);
        playerViewDistance = has(Player.class, "setViewDistance", int.class);
        playerSimDistance = has(Player.class, "setSimulationDistance", int.class);
        worldDistances = has(World.class, "setViewDistance", int.class) && has(World.class, "setSimulationDistance", int.class);
        unloadRequest = has(World.class, "unloadChunkRequest", int.class, int.class);
        tps = has(Bukkit.class, "getTPS");
        avgTickTime = has(Bukkit.class, "getAverageTickTime");
        entitiesLoaded = has(Chunk.class, "isEntitiesLoaded");
        pluginTickets = has(Chunk.class, "getPluginChunkTickets");
        chunkCount = has(World.class, "getChunkCount");
        currentTick = has(Bukkit.class, "getCurrentTick");
    }

    private static boolean has(Class<?> c, String name, Class<?>... args) {
        try { c.getMethod(name, args); return true; } catch (Throwable t) { return false; }
    }

    public static int tick() { return currentTick ? Bukkit.getCurrentTick() : (int) (System.currentTimeMillis() / 50L); }
}
