package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.PathMigrationService;
import com.blacklauncher.core.util.Validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists and manages resource packs, shader packs, and world datapacks for an instance.
 */
public final class LocalContentService {

    public enum ContentKind {
        RESOURCEPACK,
        SHADERPACK,
        DATAPACK
    }

    public record ContentEntry(
            Path path,
            String name,
            ContentKind kind,
            long sizeBytes,
            boolean enabled,
            String detail
    ) {
    }

    private LocalContentService() {
    }

    public static Path packsDir(AppConfig config, String versionId, ContentKind kind) throws LauncherException {
        requireVersion(versionId);
        if (kind != ContentKind.RESOURCEPACK && kind != ContentKind.SHADERPACK) {
            throw new LauncherException("数据包请使用存档 datapacks 目录");
        }
        DirectoryKind dirKind = kind == ContentKind.RESOURCEPACK
                ? DirectoryKind.RESOURCEPACKS
                : DirectoryKind.SHADERPACKS;
        return MinecraftLayout.forVersion(config, versionId).resolve(dirKind);
    }

    public static Path datapacksDir(AppConfig config, String versionId, String worldFolder)
            throws LauncherException {
        requireVersion(versionId);
        if (!ContentPackValidator.looksLikeWorldName(worldFolder)) {
            throw new LauncherException("非法存档名");
        }
        Path world = MinecraftLayout.forVersion(config, versionId)
                .resolve(DirectoryKind.SAVES)
                .resolve(worldFolder.trim());
        return world.resolve("datapacks");
    }

    /** List resource or shader packs under the instance folder. */
    public static List<ContentEntry> listPacks(AppConfig config, String versionId, ContentKind kind)
            throws LauncherException {
        if (kind != ContentKind.RESOURCEPACK && kind != ContentKind.SHADERPACK) {
            throw new LauncherException("listPacks 仅支持资源包 / 光影");
        }
        Path dir = packsDir(config, versionId, kind);
        return listEntriesIn(dir, kind);
    }

    /** List datapacks for one world. */
    public static List<ContentEntry> listDatapacks(AppConfig config, String versionId, String worldFolder)
            throws LauncherException {
        Path dir = datapacksDir(config, versionId, worldFolder);
        return listEntriesIn(dir, ContentKind.DATAPACK);
    }

    /**
     * List packs by kind. For {@link ContentKind#DATAPACK}, pass {@code worldFolder};
     * if blank, aggregates datapacks across all worlds (detail includes world name).
     */
    public static List<ContentEntry> list(AppConfig config, String versionId, ContentKind kind,
                                          String worldFolder) throws LauncherException {
        if (kind == ContentKind.RESOURCEPACK || kind == ContentKind.SHADERPACK) {
            return listPacks(config, versionId, kind);
        }
        if (Validation.isNonBlank(worldFolder)) {
            return listDatapacks(config, versionId, worldFolder);
        }
        List<ContentEntry> out = new ArrayList<>();
        WorldSaveService worlds = new WorldSaveService(config);
        for (WorldSaveService.WorldInfo w : worlds.listWorlds(versionId)) {
            Path dir = w.path().resolve("datapacks");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (ContentEntry e : listEntriesIn(dir, ContentKind.DATAPACK)) {
                out.add(new ContentEntry(
                        e.path(), e.name(), e.kind(), e.sizeBytes(), e.enabled(),
                        "世界: " + w.folderName()
                                + (e.detail().isBlank() ? "" : " · " + e.detail())));
            }
        }
        out.sort(Comparator.comparing(ContentEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * Hint when listing shaders without Iris/Oculus present among mods.
     * Empty string when a loader is detected or mods cannot be scanned.
     */
    public static String shaderLoaderHint(AppConfig config, String versionId) {
        if (config == null || !Validation.isSafeVersionId(versionId)) {
            return "";
        }
        try {
            List<LocalModEntry> mods = new LocalModService(config).list(versionId);
            boolean hasLoader = mods.stream()
                    .filter(m -> m.status() != LocalModEntry.Status.DISABLED)
                    .map(m -> m.fileName().toLowerCase(Locale.ROOT))
                    .anyMatch(n -> n.contains("iris") || n.contains("oculus"));
            if (hasLoader) {
                return "";
            }
            return "未检测到 Iris / Oculus：光影通常需要其一才能加载（Fabric/Quilt 用 Iris，Forge/NeoForge 用 Oculus）。";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Enable or disable a pack by renaming with a {@code .disabled} suffix
     * ({@code pack.zip} → {@code pack.zip.disabled}, folder {@code Pack} → {@code Pack.disabled}).
     */
    public static Path setEnabled(Path path, boolean enabled) throws LauncherException {
        if (path == null || !Files.exists(path)) {
            throw new LauncherException("内容不存在");
        }
        GameProcessGuard.get().requireWritable(inferKind(path));
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        String targetName;
        if (enabled) {
            if (!lower.endsWith(".disabled")) {
                return path;
            }
            targetName = name.substring(0, name.length() - ".disabled".length());
            if (targetName.isBlank()) {
                throw new LauncherException("无法启用：名称无效");
            }
        } else {
            if (lower.endsWith(".disabled")) {
                return path;
            }
            targetName = name + ".disabled";
        }
        Path dest = path.getParent().resolve(targetName);
        if (Files.exists(dest) && !dest.equals(path)) {
            throw new LauncherException("目标已存在: " + targetName);
        }
        try {
            Files.move(path, dest);
            return dest;
        } catch (Exception e) {
            throw new LauncherException((enabled ? "启用" : "禁用") + "失败: " + e.getMessage(), e);
        }
    }

    public static void delete(Path path) throws LauncherException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        GameProcessGuard.get().requireWritable(inferKind(path));
        try {
            if (Files.isDirectory(path)) {
                PathMigrationService.deleteTree(path, false);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            throw new LauncherException("删除失败: " + e.getMessage(), e);
        }
    }

    /** Copy a zip or folder into {@code saves/{world}/datapacks/}. */
    public static Path installDatapack(AppConfig config, String versionId, String worldFolder,
                                       Path sourceZipOrFolder) throws LauncherException {
        if (sourceZipOrFolder == null || !Files.exists(sourceZipOrFolder)) {
            throw new LauncherException("源文件不存在");
        }
        Path destDir = datapacksDir(config, versionId, worldFolder);
        GameProcessGuard.get().requireWritable(DirectoryKind.SAVES);
        try {
            Files.createDirectories(destDir);
            String safeName = sourceZipOrFolder.getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest = destDir.resolve(safeName);
            if (Files.exists(dest)) {
                throw new LauncherException("目标已存在: " + safeName);
            }
            if (Files.isDirectory(sourceZipOrFolder)) {
                copyTree(sourceZipOrFolder, dest);
            } else {
                Files.copy(sourceZipOrFolder, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
            return dest;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("安装数据包失败: " + e.getMessage(), e);
        }
    }

    private static List<ContentEntry> listEntriesIn(Path dir, ContentKind kind) throws LauncherException {
        List<ContentEntry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> items = stream
                    .filter(p -> Files.isRegularFile(p) || Files.isDirectory(p))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
            for (Path item : items) {
                out.add(toEntry(item, kind));
            }
        } catch (Exception e) {
            throw new LauncherException("扫描内容目录失败: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(ContentEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static ContentEntry toEntry(Path path, ContentKind kind) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean enabled = !lower.endsWith(".disabled");
        long size = sizeOf(path);
        String detail;
        if (!enabled) {
            detail = "已禁用（.disabled）";
        } else if (kind == ContentKind.RESOURCEPACK) {
            var vr = ContentPackValidator.validateResourcePack(path);
            detail = vr.valid()
                    ? (Files.isDirectory(path) ? "文件夹" : "zip")
                    : String.join("; ", vr.issues());
        } else if (kind == ContentKind.SHADERPACK) {
            detail = Files.isDirectory(path) ? "文件夹" : (lower.endsWith(".zip") || lower.endsWith(".zip.disabled")
                    ? "zip" : extensionOf(name));
        } else {
            detail = Files.isDirectory(path) ? "文件夹" : (lower.contains(".zip") ? "zip" : extensionOf(name));
        }
        return new ContentEntry(path, name, kind, size, enabled, detail);
    }

    private static long sizeOf(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            if (Files.isDirectory(path)) {
                long total = 0;
                try (Stream<Path> walk = Files.walk(path)) {
                    for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                        total += Files.size(p);
                    }
                }
                return total;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static DirectoryKind inferKind(Path path) {
        if (path == null) {
            return DirectoryKind.RESOURCEPACKS;
        }
        String s = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)
                .replace('\\', '/');
        if (s.contains("/datapacks/") || s.endsWith("/datapacks")) {
            return DirectoryKind.SAVES;
        }
        if (s.contains("/shaderpacks/") || s.endsWith("/shaderpacks")) {
            return DirectoryKind.SHADERPACKS;
        }
        return DirectoryKind.RESOURCEPACKS;
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                                                                   java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Path target = dest.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                                                           java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Path target = dest.resolve(src.relativize(file).toString());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void requireVersion(String versionId) throws LauncherException {
        if (!Validation.isSafeVersionId(versionId)) {
            throw new LauncherException("非法版本 ID");
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }
}
