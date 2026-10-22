package com.blacklauncher.core.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * File hashing for integrity checks.
 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String sha1(Path file) throws Exception {
        return digest(file, "SHA-1");
    }

    public static String sha256(Path file) throws Exception {
        return digest(file, "SHA-256");
    }

    public static boolean matchesSha1(Path file, String expected) {
        if (!Validation.isNonBlank(expected) || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return expected.equalsIgnoreCase(sha1(file));
        } catch (Exception e) {
            return false;
        }
    }

    private static String digest(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
