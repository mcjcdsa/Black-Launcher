package com.blacklauncher.download;

/**
 * Game install flavour selected before downloading a version.
 */
public enum ModLoader {
    VANILLA("原版 (Vanilla)", "vanilla"),
    FABRIC("Fabric", "fabric"),
    FORGE("Forge", "forge"),
    QUILT("Quilt", "quilt"),
    NEOFORGE("NeoForge", "neoforge");

    private final String displayName;
    private final String id;

    ModLoader(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String displayName() {
        return displayName;
    }

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static ModLoader fromId(String value) {
        if (value == null || value.isBlank()) {
            return VANILLA;
        }
        for (ModLoader loader : values()) {
            if (loader.id.equalsIgnoreCase(value) || loader.displayName.equalsIgnoreCase(value)) {
                return loader;
            }
        }
        return VANILLA;
    }
}
