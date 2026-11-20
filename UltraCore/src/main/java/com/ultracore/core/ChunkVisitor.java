package com.ultracore.core;

import org.bukkit.Chunk;
import org.bukkit.entity.Entity;

public interface ChunkVisitor {
    boolean needsEntities();
    void visit(Chunk chunk, Entity[] entities);
    default void cycleStart() {}
    default void cycleDone() {}
}
