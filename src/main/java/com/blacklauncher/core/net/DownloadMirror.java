package com.blacklauncher.core.net;

import com.blacklauncher.core.util.Validation;

/**
 * Rewrites Mojang / Maven URLs to faster mirrors when configured.
 */
public final class DownloadMirror {

    private static final String BMCL = "https://bmclapi2.bangbang93.com";

    private DownloadMirror() {
    }

    public static String rewrite(String url, DownloadSource source) {
        if (!Validation.isNonBlank(url) || source == null || source == DownloadSource.OFFICIAL) {
            return url;
        }
        String u = url.trim();
        // Meta / launcher endpoints
        u = replacePrefix(u, "https://launchermeta.mojang.com", BMCL);
        u = replacePrefix(u, "https://launcher.mojang.com", BMCL);
        u = replacePrefix(u, "https://piston-meta.mojang.com", BMCL);
        u = replacePrefix(u, "https://piston-data.mojang.com", BMCL);
        // Libraries
        u = replacePrefix(u, "https://libraries.minecraft.net", BMCL + "/maven");
        // Assets
        u = replacePrefix(u, "https://resources.download.minecraft.net", BMCL + "/assets");
        return u;
    }

    public static String assetsBase(DownloadSource source) {
        if (source == DownloadSource.OFFICIAL) {
            return "https://resources.download.minecraft.net/";
        }
        return BMCL + "/assets/";
    }

    /** Official (Mojang) assets CDN base — always, for mirror fallback. */
    public static String officialAssetsBase() {
        return "https://resources.download.minecraft.net/";
    }

    /**
     * Maps a BMCL mirror URL back to the official Mojang/Maven equivalent when possible.
     */
    public static String toOfficial(String url) {
        if (!Validation.isNonBlank(url)) {
            return url;
        }
        String u = url.trim();
        if (u.startsWith(BMCL + "/assets/")) {
            return "https://resources.download.minecraft.net/" + u.substring((BMCL + "/assets/").length());
        }
        if (u.startsWith(BMCL + "/maven/")) {
            return "https://libraries.minecraft.net/" + u.substring((BMCL + "/maven/").length());
        }
        if (u.startsWith(BMCL + "/")) {
            // Generic BMCL root often mirrors launchermeta / piston paths.
            String rest = u.substring((BMCL + "/").length());
            if (rest.startsWith("mc/") || rest.startsWith("v1/") || rest.contains("version_manifest")) {
                return "https://launchermeta.mojang.com/" + rest;
            }
        }
        return u;
    }

    public static String mavenBase(DownloadSource source, String originalBase) {
        if (!Validation.isNonBlank(originalBase)) {
            return rewrite("https://libraries.minecraft.net/", source);
        }
        String base = originalBase.endsWith("/") ? originalBase : originalBase + "/";
        if (source == DownloadSource.OFFICIAL) {
            return base;
        }
        if (base.startsWith("https://libraries.minecraft.net")) {
            return BMCL + "/maven/";
        }
        return base;
    }

    public static String manifestUrl(DownloadSource source) {
        return rewrite("https://launchermeta.mojang.com/mc/game/version_manifest.json", source);
    }

    private static String replacePrefix(String url, String from, String to) {
        if (url.startsWith(from)) {
            return to + url.substring(from.length());
        }
        return url;
    }
}
