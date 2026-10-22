package com.blacklauncher.core.net;

/**
 * Download / metadata source selection.
 */
public enum DownloadSource {
    BMCLAPI("BMCLAPI 镜像（推荐国内）", "bmclapi"),
    OFFICIAL("官方源", "official");

    private final String displayName;
    private final String id;

    DownloadSource(String displayName, String id) {
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

    public static DownloadSource fromId(String value) {
        if (value == null || value.isBlank()) {
            return BMCLAPI;
        }
        for (DownloadSource source : values()) {
            if (source.id.equalsIgnoreCase(value) || source.displayName.equalsIgnoreCase(value)) {
                return source;
            }
        }
        if ("mirror".equalsIgnoreCase(value) || "cn".equalsIgnoreCase(value)) {
            return BMCLAPI;
        }
        return BMCLAPI;
    }
}
