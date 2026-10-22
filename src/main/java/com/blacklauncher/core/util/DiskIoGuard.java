package com.blacklauncher.core.util;

import com.blacklauncher.core.exception.LauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-flight checks for disk space and write permission before IO.
 * <p>
 * Probe filenames are unique per call so parallel downloads never race on the same test file
 * (Windows {@link AccessDeniedException} on shared {@code .black-launcher-write-test}).
 */
public final class DiskIoGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DiskIoGuard.class);
    private static final long MIN_FREE_BYTES = 64L * 1024 * 1024; // 64 MB safety margin
    private static final Set<String> WRITABLE_CACHE = ConcurrentHashMap.newKeySet();

    private DiskIoGuard() {
    }

    public static void ensureWritableDirectory(Path dir) throws LauncherException {
        if (dir == null) {
            throw new LauncherException("目录路径为空");
        }
        String key = dir.toAbsolutePath().normalize().toString();
        if (WRITABLE_CACHE.contains(key)) {
            return;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Files.createDirectories(dir);
                // Unique probe — never share a fixed name across parallel workers.
                Path probe = dir.resolve(".bl-write-" + UUID.randomUUID() + ".tmp");
                try {
                    Files.writeString(probe, "ok", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } finally {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (Exception deleteEx) {
                        LOG.debug("Probe cleanup delayed: {}", probe, deleteEx);
                    }
                }
                WRITABLE_CACHE.add(key);
                return;
            } catch (AccessDeniedException e) {
                last = e;
                LOG.warn("Write probe AccessDenied (attempt {}/4) on {}", attempt, dir);
                sleepQuiet(40L * attempt);
            } catch (Exception e) {
                last = e;
                // Another thread may have created the dir / briefly locked a sibling file.
                if (isLikelyTransientIo(e) && attempt < 4) {
                    LOG.warn("Write probe transient failure (attempt {}/4) on {}: {}",
                            attempt, dir, e.toString());
                    sleepQuiet(40L * attempt);
                    continue;
                }
                break;
            }
        }
        // Fallback: if directory exists and Java reports writable, accept it.
        try {
            if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                WRITABLE_CACHE.add(key);
                LOG.info("Accepting directory via isWritable fallback: {}", dir);
                return;
            }
        } catch (Exception ignored) {
            // fall through
        }
        LOG.error("Directory not writable: {}", dir, last);
        throw new LauncherException("目录不可写或无权限: " + dir.toAbsolutePath(), last);
    }

    public static void ensureSpace(Path anyPathOnVolume, long requiredBytes) throws LauncherException {
        try {
            Path path = anyPathOnVolume;
            while (path != null && !Files.exists(path)) {
                path = path.getParent();
            }
            if (path == null) {
                path = AppPaths.home();
                Files.createDirectories(path);
            }
            long usable = path.toFile().getUsableSpace();
            long need = Math.max(requiredBytes, 0) + MIN_FREE_BYTES;
            if (usable > 0 && usable < need) {
                throw new LauncherException(String.format(
                        "磁盘空间不足：需要约 %s，当前可用 %s（路径 %s）",
                        formatSize(need), formatSize(usable), path.toAbsolutePath()));
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Disk space check failed for {}", anyPathOnVolume, e);
            // Non-fatal if FS does not report usable space.
        }
    }

    public static void ensureSpaceForDownload(Path target, long expectedSize) throws LauncherException {
        Path parent = target.getParent() == null ? AppPaths.home() : target.getParent();
        ensureWritableDirectory(parent);
        long need = expectedSize > 0 ? expectedSize : 8L * 1024 * 1024;
        ensureSpace(parent, need);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean isLikelyTransientIo(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof AccessDeniedException) {
                return true;
            }
            String name = cur.getClass().getSimpleName();
            if (name.contains("FileSystemException") || name.contains("NoSuchFileException")) {
                return true;
            }
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase();
            if (msg.contains("being used by another process")
                    || msg.contains("access is denied")
                    || msg.contains("另一个程序正在使用")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
