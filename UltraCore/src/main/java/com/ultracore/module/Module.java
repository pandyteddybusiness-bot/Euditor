package com.ultracore.module;

public interface Module {
    /** Config key under {@code modules.<id>}. */
    String id();
    String displayName();
    boolean isActive();
    void enable();
    void disable();
    /** One-shot optimization pass triggered by /ultracore optimize or memory emergencies. May return a summary line. */
    default String optimizeNow() { return null; }
    /** Called only when the adaptive pressure level changes (0 = normal, 1 = elevated, 2 = critical). */
    default void onPressureChange(int oldLevel, int newLevel) {}
    /** Extra status text for /ultracore status. */
    default void describe(StringBuilder sb) {}
}
