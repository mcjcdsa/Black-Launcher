package com.blacklauncher.modpack;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadControl;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.DiskIoGuard;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.core.util.ZipSafe;
import com.blacklauncher.download.ModLoader;
import com.blacklauncher.download.ModLoaderInstallService;
import com.blacklauncher.download.VersionManifestService;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.model.VersionManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full modpack import pipeline (§6): detect → loader → overrides → online mods → report / rollback.
 */
public final class ModpackImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ModpackImportService.class);
    private static final int MAX_FILE_RETRIES = 3;

    private final AppConfig config;
    private final Database database;
    private final ModLoaderInstallService loaderInstall;
    private final VersionManifestService manifestService;
    private final ResumableDownloader downloader;
    private final CurseForgeClient curseForge;
    private final DownloadControl control;

    public ModpackImportService(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.loaderInstall = new ModLoaderInstallService(config, database);
        this.manifestService = new VersionManifestService(config);
        // Share pause/cancel with loader / game downloads.
        this.control = loaderInstall.downloadControl();
        this.downloader = new ResumableDownloader(HttpClientFactory.create(config));
        this.downloader.setControl(control);
        this.curseForge = new CurseForgeClient(config);
        this.curseForge.setControl(control);
    }

    public DownloadControl downloadControl() {
        return control;
    }

    public ModpackDescriptor inspect(Path path) throws LauncherException {
        return ModpackDetector.detect(path);
    }

    public ModpackInstallReport importPack(Path path,
                                           ModpackConflictPolicy conflictPolicy,
                                           DownloadProgressSink sink) throws LauncherException {
        ModpackDescriptor desc = ModpackDetector.detect(path);
        return importPack(desc, conflictPolicy, sink);
    }

    public ModpackInstallReport importPack(ModpackDescriptor desc,
                                           ModpackConflictPolicy conflictPolicy,
                                           DownloadProgressSink sink) throws LauncherException {
        if (desc == null || desc.getFormat() == ModpackFormat.UNKNOWN) {
            throw new LauncherException("无法识别的整合包格式");
        }
        ModpackConflictPolicy policy = conflictPolicy == null ? ModpackConflictPolicy.CANCEL : conflictPolicy;
        if (policy == ModpackConflictPolicy.CANCEL) {
            // Caller should resolve conflicts before calling; CANCEL here means abort if exists.
        }

        String baseName = Validation.sanitizeVersionId(desc.getName());
        String versionId = resolveVersionId(baseName, policy);
        if (versionId == null) {
            ModpackInstallReport cancelled = new ModpackInstallReport();
            cancelled.setSuccess(false);
            cancelled.setSummary("已取消导入");
            return cancelled;
        }

        Path createdVersionDir = AppPaths.versionsDir().resolve(versionId);
        Path createdInstance = null;
        boolean createdSomething = false;
        ModpackInstallReport report = new ModpackInstallReport();
        report.setVersionId(versionId);

        try {
            precheckDisk(desc);

            if (desc.getFormat() == ModpackFormat.FOLDER || desc.getFormat() == ModpackFormat.SIMPLE) {
                importFolderLike(desc, versionId, sink, report);
                createdSomething = true;
            } else {
                // Online / structured packs need MC + loader first.
                if (!Validation.isNonBlank(desc.getMcVersion())) {
                    throw new LauncherException("整合包未声明 Minecraft 版本");
                }
                VersionManifest.VersionEntry entry = findVersionEntry(desc.getMcVersion());
                status(sink, "安装游戏与加载器...", 0.05);
                String installedId = loaderInstall.downloadWithLoader(
                        entry,
                        desc.getLoader() == null ? ModLoader.VANILLA : desc.getLoader(),
                        desc.getLoaderVersion(),
                        versionId,
                        sink);
                versionId = installedId;
                report.setVersionId(versionId);
                createdVersionDir = AppPaths.versionsDir().resolve(versionId);
                createdSomething = true;

                MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);
                layout.ensureUserDirectories();
                createdInstance = layout.instanceRoot();

                status(sink, "解压 overrides / 内置模组...", 0.55);
                extractOverridesAndEmbedded(desc, layout, sink);
                persistPackIndex(desc, layout);

                status(sink, "下载在线模组...", 0.60);
                downloadOnlineFiles(desc, layout, sink, report);
            }

            try {
                String loaderId = desc.getLoader() == null ? ModLoader.VANILLA.id() : desc.getLoader().id();
                database.upsertInstalledVersion(versionId, loaderId);
            } catch (Exception e) {
                report.addWarning("写入版本数据库失败: " + e.getMessage());
            }

            boolean partial = !report.getMissing().isEmpty();
            report.setPartial(partial);
            report.setSuccess(true);
            if (partial) {
                report.setSummary("整合包「" + versionId + "」已导入，但有 "
                        + report.getMissing().size() + " 个文件缺失（可启动但可能不完整）");
            } else {
                report.setSummary("整合包「" + versionId + "」导入成功，可以启动");
            }
            if (sink != null) {
                sink.complete(report.getSummary());
            }
            return report;
        } catch (Exception e) {
            LOG.error("Modpack import failed", e);
            if (createdSomething) {
                rollback(createdVersionDir, createdInstance, versionId);
            }
            if (e instanceof LauncherException le) {
                throw le;
            }
            throw new LauncherException("导入整合包失败: " + e.getMessage(), e);
        }
    }

    private void importFolderLike(ModpackDescriptor desc, String versionId,
                                  DownloadProgressSink sink, ModpackInstallReport report) throws Exception {
        Path staging = AppPaths.downloadsTempDir().resolve("modpack-import-" + System.currentTimeMillis());
        Files.createDirectories(staging);
        try {
            if (desc.getSourceArchive() != null) {
                status(sink, "解压整合包...", 0.1);
                ZipSafe.extract(desc.getSourceArchive(), staging, (done, total) -> {
                    if (sink != null && total > 0) {
                        sink.status("解压 " + done + "/" + total, 0.1 + 0.3 * done / (double) total);
                    }
                });
            } else if (desc.getSourceFolder() != null) {
                status(sink, "复制整合包文件夹...", 0.1);
                copyTree(desc.getSourceFolder(), staging);
            } else {
                throw new LauncherException("无有效整合包来源");
            }

            Path contentRoot = staging;
            Path nestedMc = staging.resolve(".minecraft");
            if (Files.isDirectory(nestedMc)) {
                contentRoot = nestedMc;
            }

            // If versions/<id> exists inside, prefer that profile.
            Path versionsDir = contentRoot.resolve("versions");
            String profileId = versionId;
            if (Files.isDirectory(versionsDir)) {
                try (var stream = Files.list(versionsDir)) {
                    Path first = stream.filter(Files::isDirectory).findFirst().orElse(null);
                    if (first != null) {
                        String srcId = first.getFileName().toString();
                        Path dest = AppPaths.versionsDir().resolve(versionId);
                        if (Files.exists(dest)) {
                            ModLoaderInstallService.deleteRecursive(dest);
                        }
                        copyTree(first, dest);
                        // Rename json/jar if needed
                        Path json = dest.resolve(srcId + ".json");
                        Path jsonNew = dest.resolve(versionId + ".json");
                        if (Files.isRegularFile(json) && !srcId.equals(versionId)) {
                            String text = Files.readString(json).replace("\"id\": \"" + srcId + "\"",
                                    "\"id\": \"" + versionId + "\"");
                            Files.writeString(jsonNew, text);
                            Files.deleteIfExists(json);
                        }
                        Path jar = dest.resolve(srcId + ".jar");
                        Path jarNew = dest.resolve(versionId + ".jar");
                        if (Files.isRegularFile(jar) && !Files.isRegularFile(jarNew)) {
                            Files.move(jar, jarNew);
                        }
                        profileId = versionId;
                        report.addInstalled("版本配置 " + profileId);
                    }
                }
            } else if (desc.getFormat() == ModpackFormat.SIMPLE) {
                // Need at least a vanilla/base — user must have MC; create placeholder note.
                report.addWarning("简易整合包未包含版本 JSON，请确保已安装对应 Minecraft 版本后再手动关联");
            }

            MinecraftLayout layout = MinecraftLayout.forVersion(config, profileId);
            layout.ensureUserDirectories();
            status(sink, "复制 mods / config 等...", 0.55);
            copyUserContent(contentRoot, layout, report);
            persistPackIndex(desc, layout);
            // Also copy index if present at content root (folder packs).
            copyIndexIfPresent(contentRoot, layout.instanceRoot());
            report.setVersionId(profileId);
        } finally {
            try {
                ModLoaderInstallService.deleteRecursive(staging);
            } catch (Exception ignored) {
                // temp cleanup
            }
        }
    }

    /** Keep pack index under instance root so {@link ModpackRepairService} can re-check files. */
    private void persistPackIndex(ModpackDescriptor desc, MinecraftLayout layout) {
        try {
            Path destRoot = layout.instanceRoot();
            Files.createDirectories(destRoot);
            Path archive = desc.getSourceArchive();
            Path folder = desc.getSourceFolder();
            if (archive != null && Files.isRegularFile(archive)) {
                if (ZipSafe.containsEntry(archive, "modrinth.index.json")) {
                    Files.writeString(destRoot.resolve("modrinth.index.json"),
                            ZipSafe.readEntryUtf8(archive, "modrinth.index.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                if (ZipSafe.containsEntry(archive, "manifest.json")) {
                    Files.writeString(destRoot.resolve("manifest.json"),
                            ZipSafe.readEntryUtf8(archive, "manifest.json"),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            } else if (folder != null && Files.isDirectory(folder)) {
                copyIndexIfPresent(folder, destRoot);
            }
        } catch (Exception e) {
            LOG.debug("Could not persist pack index: {}", e.toString());
        }
    }

    private static void copyIndexIfPresent(Path fromDir, Path destRoot) throws Exception {
        Path mr = fromDir.resolve("modrinth.index.json");
        if (Files.isRegularFile(mr)) {
            Files.copy(mr, destRoot.resolve("modrinth.index.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path cf = fromDir.resolve("manifest.json");
        if (Files.isRegularFile(cf)) {
            Files.copy(cf, destRoot.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractOverridesAndEmbedded(ModpackDescriptor desc, MinecraftLayout layout,
                                             DownloadProgressSink sink) throws Exception {
        Path archive = desc.getSourceArchive();
        Path folder = desc.getSourceFolder();
        if (archive != null) {
            String overrides = Validation.isNonBlank(desc.getOverridesDir()) ? desc.getOverridesDir() : "overrides";
            ZipSafe.extractPrefix(archive, overrides, layout.instanceRoot());
            if (desc.isHasEmbeddedMods() || desc.getFormat() == ModpackFormat.CURSEFORGE_EMBEDDED) {
                ZipSafe.extractPrefix(archive, "mods", layout.resolve(DirectoryKind.MODS));
            }
            // mrpack may also ship client overrides only — already covered.
        } else if (folder != null) {
            Path overrides = folder.resolve(Validation.isNonBlank(desc.getOverridesDir())
                    ? desc.getOverridesDir() : "overrides");
            if (Files.isDirectory(overrides)) {
                copyTree(overrides, layout.instanceRoot());
            }
            Path mods = folder.resolve("mods");
            if (Files.isDirectory(mods) && desc.isHasEmbeddedMods()) {
                copyTree(mods, layout.resolve(DirectoryKind.MODS));
            }
        }
        if (sink != null) {
            sink.status("覆盖文件完成", 0.58);
        }
    }

    private void downloadOnlineFiles(ModpackDescriptor desc, MinecraftLayout layout,
                                     DownloadProgressSink sink, ModpackInstallReport report) {
        var files = desc.getFiles();
        if (files == null || files.isEmpty()) {
            return;
        }
        int total = files.size();
        int index = 0;
        for (ModpackFileEntry file : files) {
            index++;
            if (file.isClientOptional()) {
                continue;
            }
            String label = file.getPath() == null ? ("file#" + index) : file.getPath();
            status(sink, "模组 " + index + "/" + total + ": " + label, 0.60 + 0.35 * index / (double) total);
            try {
                Path dest = resolveDest(layout, file.getPath());
                Files.createDirectories(dest.getParent());
                boolean ok = false;
                Exception last = null;
                for (int attempt = 1; attempt <= MAX_FILE_RETRIES && !ok; attempt++) {
                    try {
                        AtomicLong lastBytes = new AtomicLong(0);
                        BiConsumerProgress progress = (done, tot) -> {
                            long prev = lastBytes.getAndSet(done);
                            long delta = done - prev;
                            if (delta > 0 && sink != null) {
                                sink.addBytes(delta);
                            }
                        };
                        if (!file.getDownloads().isEmpty()) {
                            LauncherException dlLast = null;
                            for (String url : file.getDownloads()) {
                                try {
                                    downloader.download(url, dest, file.getSha1(), file.getFileSize(), progress);
                                    ok = true;
                                    break;
                                } catch (LauncherException e) {
                                    dlLast = e;
                                }
                            }
                            if (!ok && dlLast != null) {
                                throw dlLast;
                            }
                        } else if (file.getCurseProjectId() > 0 && file.getCurseFileId() > 0) {
                            curseForge.downloadFile(file.getCurseProjectId(), file.getCurseFileId(), dest, progress);
                            ok = true;
                        } else {
                            throw new LauncherException("无下载地址");
                        }
                    } catch (Exception e) {
                        last = e;
                        try {
                            Thread.sleep(300L * attempt * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (ok) {
                    report.addInstalled(label);
                } else {
                    String msg = label + (last == null ? "" : (" — " + last.getMessage()));
                    if (file.isRequired()) {
                        report.addMissing(msg);
                    } else {
                        report.addWarning("可选文件跳过: " + msg);
                    }
                }
            } catch (Exception e) {
                report.addMissing(label + " — " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface BiConsumerProgress extends java.util.function.BiConsumer<Long, Long> {
    }

    private Path resolveDest(MinecraftLayout layout, String relative) throws LauncherException {
        String rel = relative == null ? "mods/unknown.jar" : relative.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        try {
            return ZipSafe.resolveSafe(layout.instanceRoot().toAbsolutePath().normalize(), rel);
        } catch (Exception e) {
            throw new LauncherException("非法模组路径: " + relative, e);
        }
    }

    private void copyUserContent(Path contentRoot, MinecraftLayout layout, ModpackInstallReport report)
            throws Exception {
        copyIfPresent(contentRoot.resolve("mods"), layout.resolve(DirectoryKind.MODS), report, "mods");
        copyIfPresent(contentRoot.resolve("config"), layout.resolve(DirectoryKind.CONFIG), report, "config");
        copyIfPresent(contentRoot.resolve("resourcepacks"), layout.resolve(DirectoryKind.RESOURCEPACKS), report, "resourcepacks");
        copyIfPresent(contentRoot.resolve("shaderpacks"), layout.resolve(DirectoryKind.SHADERPACKS), report, "shaderpacks");
        copyIfPresent(contentRoot.resolve("saves"), layout.resolve(DirectoryKind.SAVES), report, "saves");
        Path servers = contentRoot.resolve("servers.dat");
        if (Files.isRegularFile(servers)) {
            Files.copy(servers, layout.resolve(DirectoryKind.SERVERS_DAT), StandardCopyOption.REPLACE_EXISTING);
            report.addInstalled("servers.dat");
        }
        Path options = contentRoot.resolve("options.txt");
        if (Files.isRegularFile(options)) {
            Files.copy(options, layout.resolve(DirectoryKind.OPTIONS_TXT), StandardCopyOption.REPLACE_EXISTING);
            report.addInstalled("options.txt");
        }
    }

    private void copyIfPresent(Path src, Path dst, ModpackInstallReport report, String label) throws Exception {
        if (Files.isDirectory(src)) {
            copyTree(src, dst);
            report.addInstalled(label);
        }
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        try (var walk = Files.walk(src)) {
            walk.forEach(p -> {
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
        } catch (RuntimeException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private String resolveVersionId(String baseName, ModpackConflictPolicy policy) throws LauncherException {
        Path dir = AppPaths.versionsDir().resolve(baseName);
        if (!Files.exists(dir) && !Files.exists(AppPaths.gameDir().resolve("instances").resolve(baseName))) {
            return baseName;
        }
        return switch (policy) {
            case OVERWRITE -> {
                try {
                    ModLoaderInstallService.deleteRecursive(dir);
                    ModLoaderInstallService.deleteRecursive(
                            AppPaths.gameDir().resolve("instances").resolve(baseName));
                } catch (Exception e) {
                    throw new LauncherException("清理旧版本失败: " + e.getMessage(), e);
                }
                yield baseName;
            }
            case COPY_SUFFIX -> {
                for (int i = 2; i < 1000; i++) {
                    String candidate = baseName + "_" + i;
                    if (!Files.exists(AppPaths.versionsDir().resolve(candidate))) {
                        yield candidate;
                    }
                }
                throw new LauncherException("无法分配副本版本名");
            }
            case CANCEL -> null;
        };
    }

    private VersionManifest.VersionEntry findVersionEntry(String mcVersion) throws LauncherException {
        VersionManifest manifest = manifestService.fetch();
        if (manifest.getVersions() != null) {
            for (VersionManifest.VersionEntry e : manifest.getVersions()) {
                if (mcVersion.equals(e.getId())) {
                    return e;
                }
            }
        }
        // Fallback synthetic entry — GameDownloadService needs url from manifest ideally.
        throw new LauncherException("版本清单中未找到 Minecraft " + mcVersion);
    }

    private void precheckDisk(ModpackDescriptor desc) throws LauncherException {
        try {
            long estimate = 512L * 1024 * 1024;
            if (desc.getSourceArchive() != null && Files.isRegularFile(desc.getSourceArchive())) {
                estimate = Math.max(estimate, Files.size(desc.getSourceArchive()) * 3);
            }
            for (ModpackFileEntry f : desc.getFiles()) {
                if (f.getFileSize() > 0) {
                    estimate += f.getFileSize();
                } else {
                    estimate += 5L * 1024 * 1024;
                }
            }
            DiskIoGuard.ensureSpace(AppPaths.gameDir(), estimate);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            // DiskIoGuard may throw; if helper absent API differs — soft fail
            LOG.debug("Disk precheck skipped: {}", e.toString());
        }
    }

    private void rollback(Path versionDir, Path instanceDir, String versionId) {
        try {
            if (versionDir != null) {
                ModLoaderInstallService.deleteRecursive(versionDir);
            }
            if (instanceDir != null) {
                ModLoaderInstallService.deleteRecursive(instanceDir);
            } else if (Validation.isNonBlank(versionId)) {
                ModLoaderInstallService.deleteRecursive(
                        AppPaths.gameDir().resolve("instances").resolve(versionId));
            }
        } catch (Exception e) {
            LOG.warn("Rollback incomplete for {}", versionId, e);
        }
    }

    private static void status(DownloadProgressSink sink, String msg, double p) {
        if (sink != null) {
            sink.status(msg, p);
        }
    }
}
