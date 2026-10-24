package com.blacklauncher.memory;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Probes host physical memory via {@link com.sun.management.OperatingSystemMXBean}.
 */
public final class SystemMemoryProbe {

    public record Snapshot(long totalBytes, long freeBytes, long usedBytes) {
    }

    private SystemMemoryProbe() {
    }

    public static Snapshot probe() {
        long total = 0L;
        long free = 0L;
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                total = os.getTotalMemorySize();
                free = os.getFreeMemorySize();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        if (total <= 0) {
            total = Runtime.getRuntime().maxMemory();
            if (total <= 0 || total == Long.MAX_VALUE) {
                total = 8L * 1024 * 1024 * 1024; // last-resort guess
            }
            free = Runtime.getRuntime().freeMemory();
        }
        free = Math.max(0L, Math.min(free, total));
        long used = Math.max(0L, total - free);
        return new Snapshot(total, free, used);
    }

    public static long bytesToMb(long bytes) {
        return Math.max(0L, bytes / (1024L * 1024L));
    }

    public static String formatMb(long bytes) {
        return bytesToMb(bytes) + "MB";
    }

    public static String formatMbValue(long mb) {
        return mb + "MB";
    }

    public static String formatPercent(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return "0%";
        }
        double pct = (usedBytes * 100.0) / totalBytes;
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }
}
