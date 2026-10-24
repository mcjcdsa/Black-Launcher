package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Renames installed version folders / registration ids without touching game binaries (§10.4).
 */
public final class VersionRenameService {

    private static final Logger LOG = LoggerFactory.getLogger(VersionRenameService.class);
    private static final String ORIGIN_FILE = ".blacklauncher-origin";

    private final AppConfig config;
    private final Database database;

    public VersionRenameService(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
    }

    public String rename(String fromId, String rawNewName) throws LauncherException {
        Validation.requireSafeVersionId(fromId);
        String toId = Validation.sanitizeVersionId(rawNewName);
        if (!Validation.isSafeVersionId(toId)) {
            throw new LauncherException("版本名称不合规，请使用中英文、数字或常规符号");
        }
        if (toId.length() > 64) {
            throw new LauncherException("版本名称过长（最多 64 个字符）");
        }
        if (fromId.equals(toId)) {
            return toId;
        }
        Path src = AppPaths.versionsDir().resolve(fromId);
        Path dst = AppPaths.versionsDir().resolve(toId);
        if (!Files.isDirectory(src)) {
            throw new LauncherException("源版本不存在: " + fromId);
        }
        if (Files.exists(dst)) {
            throw new LauncherException("目标版本已存在: " + toId);
        }

        String origin = readOrigin(src);
        if (!Validation.isNonBlank(origin)) {
            origin = fromId;
        }

        try {
            moveRecursive(src, dst);
            rewriteProfileFiles(dst, fromId, toId);
            writeOrigin(dst, origin);
            moveInstanceIfNeeded(fromId, toId);
            moveNativesIfNeeded(fromId, toId);
            rewriteInheritsFromReferences(fromId, toId);

            String type = "custom";
            try {
                for (Database.InstalledVersionRecord r : database.listInstalledVersions()) {
                    if (fromId.equals(r.id())) {
                        type = r.type();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // keep default
            }
            database.deleteInstalledVersion(fromId);
            database.upsertInstalledVersion(toId, type);

            if (config != null && fromId.equals(config.getSelectedVersion())) {
                config.setSelectedVersion(toId);
            }
            LOG.info("Renamed version {} -> {} (origin={})", fromId, toId, origin);
            return toId;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("重命名版本失败: " + e.getMessage(), e);
        }
    }

    public String restoreDefaultName(String currentId) throws LauncherException {
        Validation.requireSafeVersionId(currentId);
        Path dir = AppPaths.versionsDir().resolve(currentId);
        String origin = readOrigin(dir);
        if (!Validation.isNonBlank(origin)) {
            throw new LauncherException("未记录官方默认名称，无法恢复");
        }
        if (origin.equals(currentId)) {
            return currentId;
        }
        return rename(currentId, origin);
    }

    public String originName(String versionId) {
        if (!Validation.isSafeVersionId(versionId)) {
            return "";
        }
        return readOrigin(AppPaths.versionsDir().resolve(versionId));
    }

    private void rewriteProfileFiles(Path versionDir, String fromId, String toId) throws Exception {
        Path json = versionDir.resolve(fromId + ".json");
        Path jsonNew = versionDir.resolve(toId + ".json");
        if (Files.isRegularFile(json)) {
            VersionJson v = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
            if (v != null) {
                v.setId(toId);
                Files.writeString(jsonNew, JsonUtil.toJsonPretty(v), StandardCharsets.UTF_8);
            }
            if (!json.equals(jsonNew)) {
                Files.deleteIfExists(json);
            }
        }
        Path jar = versionDir.resolve(fromId + ".jar");
        Path jarNew = versionDir.resolve(toId + ".jar");
        if (Files.isRegularFile(jar) && !jar.equals(jarNew)) {
            Files.move(jar, jarNew, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rewriteInheritsFromReferences(String fromId, String toId) {
        Path versions = AppPaths.versionsDir();
        if (!Files.isDirectory(versions)) {
            return;
        }
        try (var stream = Files.list(versions)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                try {
                    VersionJson v = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
                    if (v != null && fromId.equals(v.getInheritsFrom())) {
                        v.setInheritsFrom(toId);
                        Files.writeString(json, JsonUtil.toJsonPretty(v), StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    LOG.warn("Skip inheritsFrom rewrite for {}", id, e);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed scanning versions for inheritsFrom", e);
        }
    }

    private void moveInstanceIfNeeded(String fromId, String toId) throws Exception {
        Path from = AppPaths.gameDir().resolve("instances").resolve(fromId);
        Path to = AppPaths.gameDir().resolve("instances").resolve(toId);
        if (Files.isDirectory(from) && !Files.exists(to)) {
            moveRecursive(from, to);
        }
    }

    private void moveNativesIfNeeded(String fromId, String toId) throws Exception {
        Path from = AppPaths.nativesDir().resolve(fromId);
        Path to = AppPaths.nativesDir().resolve(toId);
        if (Files.isDirectory(from) && !Files.exists(to)) {
            moveRecursive(from, to);
        }
    }

    private static void moveRecursive(Path src, Path dst) throws Exception {
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst);
            return;
        } catch (Exception ignored) {
            // cross-device fallback: copy then delete
        }
        Files.walk(src).forEach(p -> {
            try {
                Path rel = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(rel);
                } else {
                    Files.createDirectories(rel.getParent());
                    Files.copy(p, rel, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try (var walk = Files.walk(src)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        }
    }

    private static String readOrigin(Path versionDir) {
        try {
            Path f = versionDir.resolve(ORIGIN_FILE);
            if (Files.isRegularFile(f)) {
                return Files.readString(f, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
            // none
        }
        return "";
    }

    private static void writeOrigin(Path versionDir, String origin) throws Exception {
        if (!Validation.isNonBlank(origin)) {
            return;
        }
        Files.writeString(versionDir.resolve(ORIGIN_FILE), origin.trim(), StandardCharsets.UTF_8);
    }
}
