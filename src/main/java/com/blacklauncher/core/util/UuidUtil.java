package com.blacklauncher.core.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Offline UUID helpers compatible with Minecraft offline mode.
 */
public final class UuidUtil {

    private UuidUtil() {
    }

    public static String offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    public static String withDashes(String uuid) {
        if (uuid == null) {
            return "";
        }
        String compact = uuid.replace("-", "");
        if (compact.length() != 32) {
            return uuid;
        }
        return compact.substring(0, 8) + "-"
                + compact.substring(8, 12) + "-"
                + compact.substring(12, 16) + "-"
                + compact.substring(16, 20) + "-"
                + compact.substring(20);
    }
}
