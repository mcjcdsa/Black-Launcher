package com.blacklauncher.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for extracting Minecraft version ids from loader profile names.
 */
public final class McVersionUtil {

    private static final Pattern MC_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private McVersionUtil() {
    }

    /**
     * Best-effort extract of base MC version from ids like fabric-loader-0.16-1.20.1.
     */
    public static String extractBaseVersion(String versionId) {
        if (!Validation.isNonBlank(versionId)) {
            return "";
        }
        if (Validation.isSafeVersionId(versionId) && versionId.matches("\\d+\\.\\d+(?:\\.\\d+)?")) {
            return versionId;
        }
        Matcher matcher = MC_VERSION.matcher(versionId);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }
}
