package com.blacklauncher.download;

import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Imports an external Minecraft game folder (.minecraft / versions / single version)
 * into the launcher data directory and registers recognized profiles.
 */
public class GameFolderImportService {

    private static final Logger LOG = LoggerFactory.getLogger(GameFolderImportService.class);

    private final Database database;

    public GameFolderImportService(Database database) {
        this.database = database;
    }

    public enum RootKind {
        MINECRAFT_ROOT,
        VERSIONS_DIR,
        SINGLE_VERSION,
        UNKNOWN
    }

    public record DetectedVersion(
            String id,
            String type,
            String inheritsFrom,
            Path sourceDir,
            boolean hasJar,
            boolean launchable,
            int modCount,
            int shaderCount,
            int saveCount,
            boolean alreadyInstalled,
            String note
    ) {
        public String summaryLine() {
            StringBuilder sb = new StringBuilder(id);
            sb.append("  [").append(type == null || type.isBlank() ? "unknown" : type).append(']');
            if (Validation.isNonBlank(inheritsFrom)) {
                sb.append("  ← ").append(inheritsFrom);
            }
            if (!hasJar && launchable) {
                sb.append("  (继承 jar)");
            } else if (!launchable) {
                sb.append("  (缺 jar)");
            }
            if (modCount > 0) {
                sb.append("  Mod ").append(modCount);
            }
            if (shaderCount > 0) {
                sb.append("  光影 ").append(shaderCount);
            }
            if (saveCount > 0) {
                sb.append("  存档 ").append(saveCount);
            }
            if (alreadyInstalled) {
                sb.append("  ·已存在");
            }
            if (Validation.isNonBlank(note)) {
                sb.append("  ·").append(note);
            }
            return sb.toString();
        }
    }

    public record ScanResult(
            Path selectedPath,
            RootKind kind,
            Path minecraftRoot,
            Path versionsDir,
            List<DetectedVersion> versions,
            boolean hasLibraries,
            boolean hasAssets
    ) {
    }

    public record ImportOptions(
            boolean overwrite,
            boolean copyLibraries,
            boolean copyAssets,
            boolean copyInstanceContent
    ) {
        public static ImportOptions defaults() {
            return new ImportOptions(false, true, true, true);
        }
    }

    public record ImportResult(
            int imported,
            int skipped,
            int failed,
            List<String> importedIds,
            List<String> messages
    ) {
    }

    public ScanResult scan(Path selected) throws LauncherException {
        if (selected == null || !Files.isDirectory(selected)) {
            throw new LauncherException("请选择有效的游戏文件夹");
        }
        Path abs = selected.toAbsolutePath().normalize();
        ResolvedRoot root = resolveRoot(abs);
        List<DetectedVersion> versions = new ArrayList<>();
        if (root.versionsDir() != null && Files.isDirectory(root.versionsDir())) {
            try (Stream<Path> stream = Files.list(root.versionsDir())) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(dir -> {
                            DetectedVersion v = inspectVersionDir(dir, root.minecraftRoot());
                            if (v != null) {
                                versions.add(v);
                            }
                        });
            } catch (IOException e) {
                throw new LauncherException("扫描 versions 目录失败", e);
            }
        } else if (root.kind() == RootKind.SINGLE_VERSION) {
            DetectedVersion v = inspectVersionDir(abs, root.minecraftRoot());
            if (v != null) {
                versions.add(v);
            }
        }

        boolean hasLibs = root.minecraftRoot() != null
                && Files.isDirectory(root.minecraftRoot().resolve("libraries"));
        boolean hasAssets = root.minecraftRoot() != null
                && Files.isDirectory(root.minecraftRoot().resolve("assets"));

        return new ScanResult(abs, root.kind(), root.minecraftRoot(), root.versionsDir(),
                List.copyOf(versions), hasLibs, hasAssets);
    }

    public ImportResult importSelected(ScanResult scan,
                                       List<DetectedVersion> chosen,
                                       ImportOptions options) throws LauncherException {
        if (scan == null || chosen == null || chosen.isEmpty()) {
            throw new LauncherException("没有可导入的版本");
        }
        ImportOptions opts = options == null ? ImportOptions.defaults() : options;
        AppPaths.ensureDirectories();

        List<String> importedIds = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        int skipped = 0;
        int failed = 0;

        if (opts.copyLibraries() && scan.hasLibraries() && scan.minecraftRoot() != null) {
            try {
                int n = mergeTree(scan.minecraftRoot().resolve("libraries"), AppPaths.librariesDir(), false);
                messages.add("已合并依赖库文件约 " + n + " 个");
            } catch (Exception e) {
                LOG.warn("Merge libraries failed", e);
                messages.add("依赖库合并部分失败: " + e.getMessage());
            }
        }
        if (opts.copyAssets() && scan.hasAssets() && scan.minecraftRoot() != null) {
            try {
                int n = mergeTree(scan.minecraftRoot().resolve("assets"), AppPaths.assetsDir(), false);
                messages.add("已合并资源文件约 " + n + " 个");
            } catch (Exception e) {
                LOG.warn("Merge assets failed", e);
                messages.add("资源合并部分失败: " + e.getMessage());
            }
        }

        for (DetectedVersion ver : chosen) {
            try {
                if (!Validation.isSafeVersionId(ver.id())) {
                    failed++;
                    messages.add("跳过非法版本 ID: " + ver.id());
                    continue;
                }
                Path dest = AppPaths.versionsDir().resolve(ver.id());
                if (Files.exists(dest) && !opts.overwrite()) {
                    skipped++;
                    messages.add("已存在，跳过: " + ver.id());
                    continue;
                }
                if (Files.exists(dest) && opts.overwrite()) {
                    deleteRecursive(dest);
                }
                copyTree(ver.sourceDir(), dest);
                ensureClientJar(ver, dest);

                String type = Validation.isNonBlank(ver.type()) ? ver.type() : "release";
                database.upsertInstalledVersion(ver.id(), type);

                if (opts.copyInstanceContent()) {
                    copyInstanceContent(scan.minecraftRoot(), ver, dest);
                }

                importedIds.add(ver.id());
                LOG.info("Imported version {} from {}", ver.id(), ver.sourceDir());
            } catch (Exception e) {
                failed++;
                LOG.error("Import failed for {}", ver.id(), e);
                messages.add("导入失败 " + ver.id() + ": " + e.getMessage());
            }
        }

        return new ImportResult(importedIds.size(), skipped, failed, List.copyOf(importedIds), List.copyOf(messages));
    }

    private void ensureClientJar(DetectedVersion ver, Path destDir) throws IOException {
        Path jar = destDir.resolve(ver.id() + ".jar");
        if (Files.isRegularFile(jar)) {
            return;
        }
        // Copy parent jar into profile dir when possible so Home list recognizes it.
        if (Validation.isNonBlank(ver.inheritsFrom()) && Validation.isSafeVersionId(ver.inheritsFrom())) {
            Path parentJar = AppPaths.versionsDir()
                    .resolve(ver.inheritsFrom())
                    .resolve(ver.inheritsFrom() + ".jar");
            if (Files.isRegularFile(parentJar)) {
                Files.copy(parentJar, jar, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Path srcParent = ver.sourceDir().getParent();
            if (srcParent != null) {
                Path sibling = srcParent.resolve(ver.inheritsFrom()).resolve(ver.inheritsFrom() + ".jar");
                if (Files.isRegularFile(sibling)) {
                    Files.copy(sibling, jar, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void copyInstanceContent(Path minecraftRoot, DetectedVersion ver, Path versionDest)
            throws IOException {
        Path instance = AppPaths.gameDir().resolve("instances").resolve(ver.id());
        Files.createDirectories(instance);

        // Prefer content living next to the version folder (some launchers); else .minecraft root.
        Path[] roots = minecraftRoot == null
                ? new Path[]{ver.sourceDir()}
                : new Path[]{ver.sourceDir(), minecraftRoot};

        for (String folder : List.of("mods", "shaderpacks", "resourcepacks", "saves", "config")) {
            for (Path root : roots) {
                if (root == null) {
                    continue;
                }
                Path src = root.resolve(folder);
                if (Files.isDirectory(src)) {
                    mergeTree(src, instance.resolve(folder), true);
                    break;
                }
            }
        }

        // options.txt at .minecraft root
        if (minecraftRoot != null) {
            Path options = minecraftRoot.resolve("options.txt");
            if (Files.isRegularFile(options)) {
                Path dest = instance.resolve("options.txt");
                if (!Files.exists(dest)) {
                    Files.copy(options, dest);
                }
            }
        }

        // Keep a marker that this version was imported (optional, harmless)
        Path marker = versionDest.resolve(".black-imported");
        if (!Files.exists(marker)) {
            Files.writeString(marker, "imported-from=" + ver.sourceDir().toAbsolutePath(), StandardCharsets.UTF_8);
        }
    }

    private DetectedVersion inspectVersionDir(Path dir, Path minecraftRoot) {
        String id = dir.getFileName().toString();
        if (!Validation.isSafeVersionId(id)) {
            return null;
        }
        Path jsonPath = dir.resolve(id + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            // Some folders use mismatched json name — try any single *.json
            try (Stream<Path> stream = Files.list(dir)) {
                jsonPath = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                return null;
            }
            if (jsonPath == null) {
                return null;
            }
        }

        String type = "unknown";
        String inherits = null;
        String note = null;
        try {
            VersionJson parsed = JsonUtil.fromJson(
                    Files.readString(jsonPath, StandardCharsets.UTF_8), VersionJson.class);
            if (parsed != null) {
                if (Validation.isNonBlank(parsed.getType())) {
                    type = parsed.getType();
                }
                inherits = parsed.getInheritsFrom();
                if (Validation.isNonBlank(parsed.getId()) && !parsed.getId().equals(id)) {
                    note = "json.id=" + parsed.getId();
                }
                String lower = id.toLowerCase(Locale.ROOT);
                if (lower.contains("fabric")) {
                    type = "fabric";
                } else if (lower.contains("quilt")) {
                    type = "quilt";
                } else if (lower.contains("neoforge")) {
                    type = "neoforge";
                } else if (lower.contains("forge")) {
                    type = "forge";
                }
            }
        } catch (Exception e) {
            note = "JSON 解析失败";
            LOG.debug("Bad version json {}", jsonPath, e);
        }

        Path jar = dir.resolve(id + ".jar");
        boolean hasJar = Files.isRegularFile(jar);
        boolean parentJar = false;
        if (!hasJar && Validation.isNonBlank(inherits) && Validation.isSafeVersionId(inherits)) {
            Path versionsParent = dir.getParent();
            if (versionsParent != null) {
                parentJar = Files.isRegularFile(
                        versionsParent.resolve(inherits).resolve(inherits + ".jar"));
            }
            if (!parentJar) {
                parentJar = Files.isRegularFile(
                        AppPaths.versionsDir().resolve(inherits).resolve(inherits + ".jar"));
            }
        }
        boolean launchable = hasJar || parentJar;

        int mods = countFiles(firstExisting(dir.resolve("mods"),
                minecraftRoot == null ? null : minecraftRoot.resolve("mods")), ".jar");
        int shaders = countFiles(firstExisting(dir.resolve("shaderpacks"),
                minecraftRoot == null ? null : minecraftRoot.resolve("shaderpacks")), null);
        int saves = countDirs(firstExisting(dir.resolve("saves"),
                minecraftRoot == null ? null : minecraftRoot.resolve("saves")));

        boolean installed = Files.isRegularFile(AppPaths.versionsDir().resolve(id).resolve(id + ".json"));

        return new DetectedVersion(id, type, inherits, dir, hasJar, launchable,
                mods, shaders, saves, installed, note);
    }

    private record ResolvedRoot(RootKind kind, Path minecraftRoot, Path versionsDir) {
    }

    private ResolvedRoot resolveRoot(Path selected) {
        // Case: .../.minecraft
        if (Files.isDirectory(selected.resolve("versions"))) {
            return new ResolvedRoot(RootKind.MINECRAFT_ROOT, selected, selected.resolve("versions"));
        }
        // Case: .../.minecraft/versions
        if (selected.getFileName() != null
                && "versions".equalsIgnoreCase(selected.getFileName().toString())) {
            Path mc = selected.getParent();
            return new ResolvedRoot(RootKind.VERSIONS_DIR, mc, selected);
        }
        // Case: single version folder containing {id}.json
        String name = selected.getFileName().toString();
        if (Files.isRegularFile(selected.resolve(name + ".json"))
                || hasAnyVersionJson(selected)) {
            Path parent = selected.getParent();
            Path mc = null;
            Path versionsDir = null;
            if (parent != null && "versions".equalsIgnoreCase(parent.getFileName().toString())) {
                versionsDir = parent;
                mc = parent.getParent();
            }
            return new ResolvedRoot(RootKind.SINGLE_VERSION, mc, versionsDir);
        }
        // Heuristic: folder that looks like game root (libraries + assets)
        if (Files.isDirectory(selected.resolve("libraries"))
                || Files.isDirectory(selected.resolve("assets"))) {
            Path versions = selected.resolve("versions");
            if (Files.isDirectory(versions)) {
                return new ResolvedRoot(RootKind.MINECRAFT_ROOT, selected, versions);
            }
        }
        return new ResolvedRoot(RootKind.UNKNOWN, null, null);
    }

    private static boolean hasAnyVersionJson(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Path firstExisting(Path a, Path b) {
        if (a != null && Files.isDirectory(a)) {
            return a;
        }
        if (b != null && Files.isDirectory(b)) {
            return b;
        }
        return null;
    }

    private static int countFiles(Path dir, String extension) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(p -> extension == null
                            || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countDirs(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int mergeTree(Path source, Path dest, boolean overwrite) throws IOException {
        if (!Files.isDirectory(source)) {
            return 0;
        }
        Files.createDirectories(dest);
        Map<Path, Path> pending = new LinkedHashMap<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = source.relativize(file);
                pending.put(file, dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
        });
        int copied = 0;
        for (Map.Entry<Path, Path> e : pending.entrySet()) {
            Path target = e.getValue();
            if (Files.exists(target) && !overwrite) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(e.getKey(), target, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        mergeTree(source, dest, true);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
