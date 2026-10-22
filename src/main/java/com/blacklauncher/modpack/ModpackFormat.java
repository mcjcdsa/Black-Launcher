package com.blacklauncher.modpack;

/**
 * Detected modpack archive / folder layout (§6.3).
 */
public enum ModpackFormat {
    MODRINTH,
    CURSEFORGE_ONLINE,
    CURSEFORGE_EMBEDDED,
    FOLDER,
    SIMPLE,
    UNKNOWN
}
