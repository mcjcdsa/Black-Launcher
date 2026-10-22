package com.blacklauncher.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Zip create/extract with path-traversal guards and basic zip-bomb limits.
 */
public final class ZipSafe {

    private static final long MAX_ENTRY_UNCOMPRESSED = 2L * 1024 * 1024 * 1024; // 2 GiB / entry
    private static final long MAX_TOTAL_UNCOMPRESSED = 8L * 1024 * 1024 * 1024; // 8 GiB total
    private static final double MAX_COMPRESSION_RATIO = 200.0;

    private ZipSafe() {
    }

    public static void extract(Path zipFile, Path targetDir) throws IOException {
        extract(zipFile, targetDir, null);
    }

    /**
     * @param progress (entriesDone, entriesTotal) — total may be -1 if unknown
     */
    public static void extract(Path zipFile, Path targetDir, BiConsumer<Integer, Integer> progress)
            throws IOException {
        Objects.requireNonNull(zipFile, "zipFile");
        Objects.requireNonNull(targetDir, "targetDir");
        Files.createDirectories(targetDir);
        Path normalizedRoot = targetDir.toAbsolutePath().normalize();

        Charset charset = StandardCharsets.UTF_8;
        try (ZipFile zip = openZip(zipFile, charset)) {
            int total = zip.size();
            int done = 0;
            long totalUncompressed = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path dest = resolveSafe(normalizedRoot, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    long size = entry.getSize();
                    long compressed = entry.getCompressedSize();
                    if (size > MAX_ENTRY_UNCOMPRESSED) {
                        throw new IOException("压缩条目过大: " + name);
                    }
                    if (size > 0 && compressed > 0 && ((double) size / compressed) > MAX_COMPRESSION_RATIO) {
                        throw new IOException("可疑压缩比（可能 zip bomb）: " + name);
                    }
                    if (size > 0) {
                        totalUncompressed += size;
                        if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                            throw new IOException("解压总量超过安全上限");
                        }
                    }
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(dest)) {
                        in.transferTo(out);
                    }
                }
                done++;
                if (progress != null) {
                    progress.accept(done, total);
                }
            }
        }
    }

    /** Extract only entries under {@code prefix} (e.g. {@code overrides/}) into {@code targetDir}. */
    public static void extractPrefix(Path zipFile, String prefix, Path targetDir) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        String p = prefix.endsWith("/") ? prefix : prefix + "/";
        Files.createDirectories(targetDir);
        Path normalizedRoot = targetDir.toAbsolutePath().normalize();
        try (ZipFile zip = openZip(zipFile, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || !name.startsWith(p)) {
                    continue;
                }
                String relative = name.substring(p.length());
                if (relative.isBlank()) {
                    continue;
                }
                Path dest = resolveSafe(normalizedRoot, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(dest)) {
                        in.transferTo(out);
                    }
                }
            }
        }
    }

    public static boolean containsEntry(Path zipFile, String exactName) throws IOException {
        try (ZipFile zip = openZip(zipFile, StandardCharsets.UTF_8)) {
            return zip.getEntry(exactName) != null || zip.getEntry("./" + exactName) != null;
        }
    }

    public static String readEntryUtf8(Path zipFile, String entryName) throws IOException {
        try (ZipFile zip = openZip(zipFile, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                entry = zip.getEntry("./" + entryName);
            }
            if (entry == null) {
                throw new IOException("缺少条目: " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    public static boolean hasEntryPrefix(Path zipFile, String prefix) throws IOException {
        String p = prefix.endsWith("/") ? prefix : prefix + "/";
        try (ZipFile zip = openZip(zipFile, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name != null && (name.equals(prefix) || name.startsWith(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasJarUnder(Path zipFile, String dirPrefix) throws IOException {
        String p = dirPrefix.endsWith("/") ? dirPrefix : dirPrefix + "/";
        try (ZipFile zip = openZip(zipFile, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name != null && name.startsWith(p) && name.toLowerCase().endsWith(".jar")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void createZip(Path sourceDir, Path zipOut) throws IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        Objects.requireNonNull(zipOut, "zipOut");
        Files.createDirectories(zipOut.getParent());
        Path root = sourceDir.toAbsolutePath().normalize();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipOut))) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String entryName = root.relativize(path).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw e;
            }
        }
    }

    public static Path resolveSafe(Path normalizedRoot, String entryName) throws IOException {
        String cleaned = entryName.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains(":/") || cleaned.contains("..")) {
            // Allow ".." only after we normalize and verify containment.
        }
        Path dest = normalizedRoot.resolve(cleaned).normalize();
        if (!dest.startsWith(normalizedRoot)) {
            throw new IOException("拒绝路径穿越: " + entryName);
        }
        return dest;
    }

    private static ZipFile openZip(Path zipFile, Charset charset) throws IOException {
        try {
            return new ZipFile(zipFile.toFile(), charset);
        } catch (IllegalArgumentException e) {
            // Some Windows-made zips use GBK names.
            return new ZipFile(zipFile.toFile(), Charset.forName("GBK"));
        }
    }
}
