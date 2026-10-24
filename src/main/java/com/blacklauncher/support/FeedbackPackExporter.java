package com.blacklauncher.support;

import com.blacklauncher.AppInfo;
import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a sanitized diagnostic zip for support (no auth tokens / secrets).
 */
public final class FeedbackPackExporter {

    private FeedbackPackExporter() {
    }

    public static Path export(AppConfig config, String versionId, Path launchLogOrNull, Path destZip)
            throws Exception {
        if (destZip == null) {
            throw new IllegalArgumentException("destZip is required");
        }
        Path parent = destZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String id = Validation.isNonBlank(versionId) ? versionId.trim() : "";
        try (OutputStream fos = Files.newOutputStream(destZip);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

            if (launchLogOrNull != null && Files.isRegularFile(launchLogOrNull)) {
                putFile(zos, "launch-log.log", launchLogOrNull);
            }

            if (Validation.isSafeVersionId(id)) {
                Path versionJson = AppPaths.versionsDir().resolve(id).resolve(id + ".json");
                if (Files.isRegularFile(versionJson)) {
                    putFile(zos, "versions/" + id + "/" + id + ".json", versionJson);
                }

                MinecraftLayout layout = MinecraftLayout.forVersion(config, id);
                Path memoryJson = layout.instanceRoot().resolve("memory.json");
                if (Files.isRegularFile(memoryJson)) {
                    putFile(zos, "memory.json", memoryJson);
                }

                Path modsDir = layout.resolve(DirectoryKind.MODS);
                putString(zos, "mods-list.txt", listModFileNames(modsDir));
            } else {
                putString(zos, "mods-list.txt", "(no version selected)\n");
            }

            for (Path log : recentLauncherLogs(3)) {
                putFile(zos, "logs/" + log.getFileName().toString(), log);
            }

            putString(zos, "meta.txt", buildMeta(config, id));
        }
        return destZip;
    }

    private static String buildMeta(AppConfig config, String versionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("app=").append(AppInfo.NAME_EN).append(' ').append(AppInfo.VERSION).append('\n');
        sb.append("os=").append(System.getProperty("os.name", ""))
                .append(' ').append(System.getProperty("os.version", ""))
                .append(" (").append(System.getProperty("os.arch", "")).append(")\n");
        sb.append("java.home=").append(System.getProperty("java.home", "")).append('\n');
        String javaPath = config == null ? "" : sanitize(config.getJavaPath());
        sb.append("configuredJava=").append(javaPath).append('\n');
        sb.append("selectedVersion=").append(sanitize(versionId)).append('\n');
        if (config != null) {
            sb.append("downloadSource=").append(sanitize(config.getDownloadSource())).append('\n');
            sb.append("isolateGameDirs=").append(config.isIsolateGameDirs()).append('\n');
            sb.append("accountType=").append(sanitize(config.getAccountType())).append('\n');
            sb.append("playerName=").append(sanitize(config.getPlayerName())).append('\n');
            sb.append("minMemoryMb=").append(config.getMinMemoryMb()).append('\n');
            sb.append("maxMemoryMb=").append(config.getMaxMemoryMb()).append('\n');
            sb.append("gameDirectory=").append(sanitize(config.getGameDirectory())).append('\n');
        }
        sb.append("note=tokens and AuthSecrets intentionally omitted\n");
        return sb.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        // Never leak token-like blobs into meta.
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("token") || lower.contains("eyj") || v.length() > 512) {
            return "(redacted)";
        }
        return v;
    }

    private static String listModFileNames(Path modsDir) throws IOException {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return "(mods directory missing)\n";
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining("\n", "", "\n"));
        }
    }

    private static List<Path> recentLauncherLogs(int limit) throws IOException {
        Path dir = AppPaths.logsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> logs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return Files.isRegularFile(p) && name.endsWith(".log");
                    })
                    .forEach(logs::add);
        }
        logs.sort(Comparator.comparingLong((Path p) -> {
            try {
                FileTime t = Files.getLastModifiedTime(p);
                return t == null ? 0L : t.toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());
        if (logs.size() > limit) {
            return new ArrayList<>(logs.subList(0, limit));
        }
        return logs;
    }

    private static void putString(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String entryName, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        try {
            entry.setTime(Files.getLastModifiedTime(file).toMillis());
        } catch (Exception ignored) {
            // optional
        }
        zos.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zos);
        }
        zos.closeEntry();
    }
}
