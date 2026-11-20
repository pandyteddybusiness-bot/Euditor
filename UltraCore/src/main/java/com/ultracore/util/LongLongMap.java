package com.ultracore.util;

import java.util.Arrays;

/**
 * Minimal open-addressing long->long map (linear probing, backward-shift deletion).
 * Avoids the per-entry object overhead of HashMap&lt;Long,Long&gt;. Main thread only.
 */
public final class LongLongMap {
    private static final long EMPTY = Long.MIN_VALUE; // reserved, never a valid key here
    private long[] keys;
    private long[] vals;
    private int size;
    private int mask;

    public LongLongMap(int expected) {
        int n = 16;
        while (n < expected * 2) n <<= 1;
        alloc(n);
    }

    private void alloc(int n) {
        keys = new long[n];
        Arrays.fill(keys, EMPTY);
        vals = new long[n];
        mask = n - 1;
        size = 0;
    }

    private int slot(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    public int size() { return size; }

    public boolean containsKey(long k) {
        int i = slot(k);
        while (keys[i] != EMPTY) {
            if (keys[i] == k) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public long get(long k, long def) {
        int i = slot(k);
        while (keys[i] != EMPTY) {
            if (keys[i] == k) return vals[i];
            i = (i + 1) & mask;
        }
        return def;
    }

    public void put(long k, long v) {
        if ((size + 1) * 2 > keys.length) rehash(keys.length << 1, Long.MIN_VALUE + 1);
        int i = slot(k);
        while (keys[i] != EMPTY) {
            if (keys[i] == k) { vals[i] = v; return; }
            i = (i + 1) & mask;
        }
        keys[i] = k;
        vals[i] = v;
        size++;
    }

    public void remove(long k) {
        int i = slot(k);
        while (keys[i] != EMPTY) {
            if (keys[i] == k) { shiftDelete(i); return; }
            i = (i + 1) & mask;
        }
    }

    private void shiftDelete(int i) {
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (keys[j] == EMPTY) break;
            int h = slot(keys[j]);
            boolean inRange = (i <= j) ? (i < h && h <= j) : (i < h || h <= j);
            if (inRange) continue;
            keys[i] = keys[j];
            vals[i] = vals[j];
            i = j;
        }
        keys[i] = EMPTY;
        size--;
    }

    /** Drops every entry whose value is below {@code min} and shrinks the table when mostly empty. */
    public void retainValuesAtLeast(long min) {
        int survivors = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY && vals[i] >= min) survivors++;
        int n = 16;
        while (n < survivors * 3) n <<= 1;
        rehash(n, min);
    }

    private void rehash(int newCap, long min) {
        long[] ok = keys, ov = vals;
        alloc(newCap);
        for (int i = 0; i < ok.length; i++) {
            if (ok[i] != EMPTY && ov[i] >= min) put(ok[i], ov[i]);
        }
    }

    public void clear() {
        if (size == 0) return;
        Arrays.fill(keys, EMPTY);
        size = 0;
    }
}
