package com.mojang.text2speech;

/**
 * DEVELOPMENT RUNS ONLY, not part of the mod jar. Stands in for the narrator library Minecraft 1.12.2 starts
 * with the game: its macOS narrator needs a native library that exists only for Intel Macs (java-objc-bridge
 * 1.0.0), so on Apple silicon the unmodified client crashes before the title screen. This no-op narrator has the
 * same three methods the game calls; it replaces the library on the classpath of runClient and runVanillaClient.
 */
public interface Narrator {
    void say(String msg);

    void clear();

    boolean active();

    static Narrator getNarrator() {
        return new Narrator() {
            @Override
            public void say(String msg) {}

            @Override
            public void clear() {}

            @Override
            public boolean active() {
                return false;
            }
        };
    }
}
