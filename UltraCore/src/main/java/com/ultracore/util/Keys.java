package com.ultracore.util;

/** Packed primitive keys so hot paths never allocate boxed Longs. */
public final class Keys {
    private Keys() {}

    public static long chunk(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int chunkX(long k) { return (int) (k >> 32); }
    public static int chunkZ(long k) { return (int) k; }

    public static long block(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
