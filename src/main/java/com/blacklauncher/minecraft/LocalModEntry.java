package com.blacklauncher.minecraft;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A local mod file under an instance {@code mods/} directory (§7).
 */
public final class LocalModEntry {

    public enum Status {
        OK("正常"),
        CORRUPT("损坏"),
        INCOMPATIBLE("不兼容"),
        DISABLED("已禁用");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Path path;
    private final String fileName;
    private final String format;
    private final long sizeBytes;
    private final Instant modified;
    private final Status status;
    private final String statusDetail;

    public LocalModEntry(Path path, String fileName, String format, long sizeBytes,
                         Instant modified, Status status, String statusDetail) {
        this.path = path;
        this.fileName = fileName;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.modified = modified;
        this.status = status;
        this.statusDetail = statusDetail == null ? "" : statusDetail;
    }

    public Path path() {
        return path;
    }

    public String fileName() {
        return fileName;
    }

    public String format() {
        return format;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Instant modified() {
        return modified;
    }

    public Status status() {
        return status;
    }

    public String statusDetail() {
        return statusDetail;
    }

    public String sizeText() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", sizeBytes / 1024.0);
        }
        return String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
