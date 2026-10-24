package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.PathMigrationService;
import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Lists, inspects, validates and backs up single-player worlds under the instance saves dir.
 */
public class WorldSaveService {

    private static final Logger LOG = LoggerFactory.getLogger(WorldSaveService.class);
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final AppConfig config;

    public WorldSaveService(AppConfig config) {
        this.config = config;
    }

    public record WorldInfo(
            String folderName,
            Path path,
            WorldSaveFormat format,
            boolean valid,
            List<String> issues,
            boolean hasDatapacks,
            List<String> dimensions,
            long levelDatBytes,
            Integer dataVersionHint
    ) {
    }

    public record BackupResult(Path archiveOrFolder, long bytesCopied) {
    }

    public List<WorldInfo> listWorlds(String versionId) throws LauncherException {
        MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);
        Path saves = layout.resolve(DirectoryKind.SAVES);
        List<WorldInfo> list = new ArrayList<>();
        if (!Files.isDirectory(saves)) {
            return list;
        }
        try (Stream<Path> stream = Files.list(saves)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(dir -> list.add(inspect(dir)));
        } catch (IOException e) {
            throw new LauncherException("扫描存档目录失败", e);
        }
        return list;
    }

    public WorldInfo inspect(Path worldDir) {
        ContentPackValidator.ValidationResult vr = ContentPackValidator.validateWorldFolder(worldDir);
        WorldSaveFormat format = detectFormat(worldDir);
        Integer dataVersion = peekDataVersion(worldDir.resolve("level.dat"));
        List<String> dimensions = listDimensions(worldDir, format);
        boolean datapacks = Files.isDirectory(worldDir.resolve("datapacks"));
        long size = 0L;
        try {
            if (Files.isRegularFile(worldDir.resolve("level.dat"))) {
                size = Files.size(worldDir.resolve("level.dat"));
            }
        } catch (IOException ignored) {
            // ignore
        }
        List<String> issues = new ArrayList<>(vr.issues());
        if (format == WorldSaveFormat.UNKNOWN && vr.valid()) {
            issues.add("未能识别存档维度结构（旧 DIM / 新 dimensions）");
        }
        return new WorldInfo(
                worldDir.getFileName().toString(),
                worldDir,
                format,
                vr.valid(),
                List.copyOf(issues),
                datapacks,
                dimensions,
                size,
                dataVersion
        );
    }

    public static WorldSaveFormat detectFormat(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return WorldSaveFormat.UNKNOWN;
        }
        if (Files.isDirectory(worldDir.resolve("dimensions"))) {
            return WorldSaveFormat.DIMENSIONS_TREE;
        }
        if (Files.isDirectory(worldDir.resolve("DIM-1"))
                || Files.isDirectory(worldDir.resolve("DIM1"))) {
            return WorldSaveFormat.LEGACY_DIM;
        }
        // Overworld-only fresh worlds may lack DIM folders until nether is visited.
        if (Files.isDirectory(worldDir.resolve("region"))
                || Files.isRegularFile(worldDir.resolve("level.dat"))) {
            Integer dv = peekDataVersion(worldDir.resolve("level.dat"));
            // Heuristic: very new data versions prefer dimensions tree when present;
            // without dimensions/ still treat as legacy-compatible layout.
            if (dv != null && dv >= 4500 && Files.isDirectory(worldDir.resolve("players"))) {
                return WorldSaveFormat.DIMENSIONS_TREE;
            }
            return WorldSaveFormat.LEGACY_DIM;
        }
        return WorldSaveFormat.UNKNOWN;
    }

    private static List<String> listDimensions(Path worldDir, WorldSaveFormat format) {
        List<String> dims = new ArrayList<>();
        if (format == WorldSaveFormat.DIMENSIONS_TREE) {
            Path mc = worldDir.resolve("dimensions").resolve("minecraft");
            if (Files.isDirectory(mc)) {
                try (Stream<Path> stream = Files.list(mc)) {
                    stream.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(dims::add);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        } else if (format == WorldSaveFormat.LEGACY_DIM) {
            dims.add("overworld");
            if (Files.isDirectory(worldDir.resolve("DIM-1"))) {
                dims.add("the_nether");
            }
            if (Files.isDirectory(worldDir.resolve("DIM1"))) {
                dims.add("the_end");
            }
        }
        return dims;
    }

    /**
     * Best-effort scan of gzipped level.dat for DataVersion int (ASCII tag name nearby).
     */
    static Integer peekDataVersion(Path levelDat) {
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return null;
        }
        try (InputStream raw = Files.newInputStream(levelDat);
             GZIPInputStream gzip = new GZIPInputStream(raw)) {
            byte[] buf = gzip.readNBytes(256 * 1024);
            String ascii = new String(buf, java.nio.charset.StandardCharsets.ISO_8859_1);
            int idx = ascii.indexOf("DataVersion");
            if (idx < 0) {
                return null;
            }
            // TAG_Int is 0x03 then name length then name then 4-byte BE int — fragile;
            // search a few bytes after the name for a plausible version (1000..20000).
            int from = idx + "DataVersion".length();
            for (int i = from; i < Math.min(buf.length - 4, from + 32); i++) {
                int v = ((buf[i] & 0xff) << 24)
                        | ((buf[i + 1] & 0xff) << 16)
                        | ((buf[i + 2] & 0xff) << 8)
                        | (buf[i + 3] & 0xff);
                if (v >= 1000 && v <= 50_000) {
                    return v;
                }
            }
        } catch (Exception e) {
            LOG.debug("peekDataVersion failed for {}", levelDat, e);
        }
        return null;
    }

    /**
     * Backs up an entire world folder (all subdirs + level.dat). Copies into
     * {@code backups/worlds/{versionId}/{name}_{timestamp}/}.
     */
    public BackupResult backupWorld(String versionId, String worldFolderName) throws LauncherException {
        GameProcessGuard.get().requireWritable(DirectoryKind.SAVES);
        if (!ContentPackValidator.looksLikeWorldName(worldFolderName)) {
            throw new LauncherException("非法存档名");
        }
        MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);
        Path source = layout.resolve(DirectoryKind.SAVES).resolve(worldFolderName);
        ContentPackValidator.ValidationResult vr = ContentPackValidator.validateWorldFolder(source);
        if (!vr.valid()) {
            throw new LauncherException("存档不合法，拒绝备份: " + String.join("; ", vr.issues()));
        }
        Path destRoot = backupWorldRoot(versionId)
                .resolve(worldFolderName + "_" + BACKUP_TS.format(LocalDateTime.now()));
        try {
            long bytes = copyTree(source, destRoot);
            LOG.info("Backed up world {} -> {} ({} bytes)", source, destRoot, bytes);
            return new BackupResult(destRoot, bytes);
        } catch (Exception e) {
            throw new LauncherException("备份存档失败: " + e.getMessage(), e);
        }
    }

    /**
     * Keeps the newest {@code keepN} backup folders for a world; deletes older ones.
     * Backup dirs are named {@code {worldFolderName}_{yyyyMMdd_HHmmss}} under
     * {@code backups/worlds/{versionId}/}.
     */
    public void rotateBackups(String versionId, String worldFolderName, int keepN) throws Exception {
        if (!ContentPackValidator.looksLikeWorldName(worldFolderName)) {
            throw new LauncherException("非法存档名");
        }
        int keep = Math.max(1, Math.min(30, keepN));
        Path root = backupWorldRoot(versionId);
        if (!Files.isDirectory(root)) {
            return;
        }
        String prefix = worldFolderName + "_";
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(matches::add);
        }
        matches.sort(Comparator.comparingLong((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());
        for (int i = keep; i < matches.size(); i++) {
            Path old = matches.get(i);
            PathMigrationService.deleteTree(old, false);
            LOG.info("Rotated away old world backup {}", old);
        }
    }

    /**
     * Copies a world folder from one version's saves dir into another's.
     */
    public void migrateWorld(String fromVersionId, String toVersionId, String worldFolderName, boolean overwrite)
            throws Exception {
        GameProcessGuard.get().requireWritable(DirectoryKind.SAVES);
        if (!Validation.isSafeVersionId(fromVersionId) || !Validation.isSafeVersionId(toVersionId)) {
            throw new LauncherException("非法版本 ID");
        }
        if (!ContentPackValidator.looksLikeWorldName(worldFolderName)) {
            throw new LauncherException("非法存档名");
        }
        if (fromVersionId.equals(toVersionId)) {
            throw new LauncherException("源版本与目标版本相同");
        }
        MinecraftLayout fromLayout = MinecraftLayout.forVersion(config, fromVersionId);
        MinecraftLayout toLayout = MinecraftLayout.forVersion(config, toVersionId);
        Path source = fromLayout.resolve(DirectoryKind.SAVES).resolve(worldFolderName);
        ContentPackValidator.ValidationResult vr = ContentPackValidator.validateWorldFolder(source);
        if (!vr.valid()) {
            throw new LauncherException("源存档不合法，拒绝迁移: " + String.join("; ", vr.issues()));
        }
        Path destSaves = toLayout.resolve(DirectoryKind.SAVES);
        Files.createDirectories(destSaves);
        Path dest = destSaves.resolve(worldFolderName);
        if (Files.exists(dest)) {
            if (!overwrite) {
                throw new LauncherException("目标版本已存在同名存档: " + worldFolderName);
            }
            PathMigrationService.deleteTree(dest, false);
        }
        copyTree(source, dest);
        LOG.info("Migrated world {} from {} to {}", worldFolderName, fromVersionId, toVersionId);
    }

    private static Path backupWorldRoot(String versionId) {
        return AppPaths.home()
                .resolve("backups")
                .resolve("worlds")
                .resolve(Validation.isSafeVersionId(versionId) ? versionId : "shared");
    }

    private static long copyTree(Path source, Path dest) throws IOException {
        Files.createDirectories(dest);
        long[] total = {0L};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                total[0] += Files.size(target);
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }
}
