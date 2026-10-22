package com.blacklauncher.download;

import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Evaluates Minecraft library / argument OS rules (HMCL-aligned).
 */
public final class OsRules {

    private OsRules() {
    }

    public static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    /** Mojang arch token used in natives classifiers ({@code ${arch}} → {@code x86} / {@code x86_64}). */
    public static String mojangArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            return "x86";
        }
        if (arch.contains("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.contains("arm")) {
            return "arm";
        }
        return "x86_64";
    }

    public static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.equals("arm64");
    }

    /**
     * Preferred modern natives classifier (e.g. {@code natives-windows-arm64} / {@code natives-windows}).
     */
    public static String nativesClassifierKey() {
        String os = currentOsName();
        boolean arm64 = isArm64();
        return switch (os) {
            case "windows" -> arm64 ? "natives-windows-arm64" : "natives-windows";
            case "osx" -> arm64 ? "natives-macos-arm64" : "natives-osx";
            default -> arm64 ? "natives-linux-arm64" : "natives-linux";
        };
    }

    /** Substitute {@code ${arch}} in classic natives map values. */
    public static String resolveClassifier(String classifier) {
        if (!Validation.isNonBlank(classifier)) {
            return classifier;
        }
        return classifier.replace("${arch}", mojangArch());
    }

    public static boolean allows(List<VersionJson.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        String current = currentOsName();
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osVersion = System.getProperty("os.version", "");
        for (VersionJson.Rule rule : rules) {
            if (!osMatches(rule.getOs(), current, arch, osVersion)) {
                continue;
            }
            allowed = "allow".equalsIgnoreCase(rule.getAction());
        }
        return allowed;
    }

    public static boolean osMatches(VersionJson.Os os, String currentOs, String arch, String osVersion) {
        if (os == null) {
            return true;
        }
        if (Validation.isNonBlank(os.getName()) && !os.getName().equalsIgnoreCase(currentOs)) {
            return false;
        }
        if (Validation.isNonBlank(os.getArch())) {
            String want = os.getArch().toLowerCase(Locale.ROOT);
            if ("x86".equals(want)) {
                if (!(arch.contains("86") && !arch.contains("64") && !arch.contains("amd"))) {
                    return false;
                }
            } else if ("x86_64".equals(want) || "amd64".equals(want)) {
                if (!(arch.contains("amd64") || (arch.contains("x86_64")) || (arch.contains("64") && !arch.contains("arm")))) {
                    return false;
                }
            } else if ("arm64".equals(want) || "aarch64".equals(want)) {
                if (!isArm64()) {
                    return false;
                }
            } else if (!arch.contains(want)) {
                return false;
            }
        }
        if (Validation.isNonBlank(os.getVersion())) {
            try {
                if (!Pattern.compile(os.getVersion()).matcher(osVersion).find()) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
