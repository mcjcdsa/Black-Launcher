package com.blacklauncher.core.util;

import java.util.regex.Pattern;

/**
 * Input validation helpers.
 */
public final class Validation {

    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    /** ASCII + CJK — version / modpack folder names. */
    private static final Pattern SAFE_PATH_SEGMENT =
            Pattern.compile("^[A-Za-z0-9._\\-+\\u4e00-\\u9fff]+$");

    private Validation() {
    }

    public static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static String sanitizePlayerName(String name) {
        if (!isNonBlank(name)) {
            return "Player";
        }
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() < 3) {
            cleaned = "Player";
        }
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        if (!PLAYER_NAME.matcher(cleaned).matches()) {
            return "Player";
        }
        return cleaned;
    }

    public static boolean isSafeVersionId(String versionId) {
        return isNonBlank(versionId) && SAFE_PATH_SEGMENT.matcher(versionId).matches();
    }

    public static void requireSafeVersionId(String versionId) {
        if (!isSafeVersionId(versionId)) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
    }

    /**
     * Turns a display name into a safe version / folder id (illegal chars → {@code _}).
     */
    public static String sanitizeVersionId(String raw) {
        if (!isNonBlank(raw)) {
            return "modpack";
        }
        StringBuilder sb = new StringBuilder(raw.trim().length());
        for (int i = 0; i < raw.trim().length(); i++) {
            char c = raw.trim().charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '+'
                    || (c >= '\u4e00' && c <= '\u9fff')) {
                sb.append(c);
            } else if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            } else if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        String out = sb.toString().replaceAll("_+", "_");
        while (out.startsWith("_")) {
            out = out.substring(1);
        }
        while (out.endsWith("_")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.isEmpty()) {
            out = "modpack";
        }
        if (out.length() > 64) {
            out = out.substring(0, 64);
        }
        if (!isSafeVersionId(out)) {
            return "modpack";
        }
        return out;
    }
}
