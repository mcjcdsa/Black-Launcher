package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.model.VersionJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers installed Minecraft versions on disk and in SQLite.
 */
public class InstalledVersionService {

    private final Database database;
    private final AppConfig config;

    public InstalledVersionService(Database database) {
        this(database, null);
    }

    public InstalledVersionService(Database database, AppConfig config) {
        this.database = database;
        this.config = config;
    }

    public List<InstalledVersion> list() throws Exception {
        Map<String, InstalledVersion> map = new LinkedHashMap<>();

        Path versionsDir = AppPaths.versionsDir();
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    String id = dir.getFileName().toString();
                    if (!Validation.isSafeVersionId(id)) {
                        return;
                    }
                    Path json = dir.resolve(id + ".json");
                    if (!Files.isRegularFile(json)) {
                        return;
                    }
                    // Own jar, or inheriting profile whose parent jar exists (Fabric/Forge).
                    if (!isRecognizedInstall(dir, id, json)) {
                        return;
                    }
                    String type = detectType(id, json);
                    map.put(id, new InstalledVersion(id, type, countMods(id), countShaders(id), countSaves(id)));
                });
            }
        }

        for (Database.InstalledVersionRecord record : database.listInstalledVersions()) {
            InstalledVersion existing = map.get(record.id());
            if (existing != null) {
                map.put(record.id(), new InstalledVersion(
                        record.id(),
                        record.type(),
                        existing.modCount(),
                        existing.shaderCount(),
                        existing.saveCount()
                ));
            }
        }
        return new ArrayList<>(map.values());
    }

    public Path gameDirFor(String versionId, boolean isolate) {
        Validation.requireSafeVersionId(versionId);
        // Prefer config-driven isolation when available; explicit flag kept for callers.
        if (isolate) {
            return AppPaths.gameDir().resolve("instances").resolve(versionId);
        }
        return AppPaths.gameDir();
    }

    /** Uses launcher config isolation switch (doc {@code 3} path priority). */
    public Path gameDirFor(String versionId) {
        return MinecraftLayout.forVersion(config, versionId).instanceRoot();
    }

    private static boolean isRecognizedInstall(Path dir, String id, Path json) {
        Path jar = dir.resolve(id + ".jar");
        if (Files.isRegularFile(jar)) {
            return true;
        }
        try {
            VersionJson parsed = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
            if (parsed != null && Validation.isNonBlank(parsed.getInheritsFrom())
                    && Validation.isSafeVersionId(parsed.getInheritsFrom())) {
                Path parentJar = AppPaths.versionsDir()
                        .resolve(parsed.getInheritsFrom())
                        .resolve(parsed.getInheritsFrom() + ".jar");
                return Files.isRegularFile(parentJar);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    private static String detectType(String id, Path json) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) {
            return "neoforge";
        }
        if (lower.contains("forge")) {
            return "forge";
        }
        if (lower.contains("fabric")) {
            return "fabric";
        }
        if (lower.contains("quilt")) {
            return "quilt";
        }
        try {
            VersionJson parsed = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
            if (parsed != null && Validation.isNonBlank(parsed.getType())) {
                return parsed.getType();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "unknown";
    }

    private int countMods(String versionId) {
        return countFiles(MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.MODS), ".jar");
    }

    private int countShaders(String versionId) {
        return countFiles(MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.SHADERPACKS), null);
    }

    private int countSaves(String versionId) {
        Path saves = MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.SAVES);
        if (!Files.isDirectory(saves)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(saves)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private int countFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(p -> extension == null || p.getFileName().toString().toLowerCase().endsWith(extension))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    public record InstalledVersion(String id, String type, int modCount, int shaderCount, int saveCount) {
    }
}
