package com.blacklauncher.modpack;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.HashUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.core.util.ZipSafe;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Re-downloads missing / mismatched modpack files from a preserved index (P1.3).
 */
public final class ModpackRepairService {

    private static final Logger LOG = LoggerFactory.getLogger(ModpackRepairService.class);
    private static final int MAX_RETRIES = 3;

    public record RepairReport(
            String versionId,
            int checked,
            int redownloaded,
            List<String> missing,
            List<String> fixed
    ) {
    }

    private final AppConfig config;
    private final ResumableDownloader downloader;
    private final CurseForgeClient curseForge;

    public ModpackRepairService(AppConfig config) {
        this.config = config;
        this.downloader = new ResumableDownloader(HttpClientFactory.create(config));
        this.curseForge = new CurseForgeClient(config);
    }

    public RepairReport repairFromIndex(AppConfig cfg, String versionId, DownloadProgressSink sink)
            throws Exception {
        AppConfig use = cfg != null ? cfg : config;
        Validation.requireSafeVersionId(versionId);
        MinecraftLayout layout = MinecraftLayout.forVersion(use, versionId);
        layout.ensureUserDirectories();

        IndexHit hit = findIndex(layout, versionId);
        if (hit == null) {
            throw new LauncherException(
                    "未找到整合包索引（modrinth.index.json / manifest.json）。"
                            + "请将索引放到实例根目录或版本目录后再试。");
        }

        ModpackDescriptor desc;
        if (hit.modrinth) {
            desc = ModrinthIndexParser.parse(hit.json);
        } else {
            desc = CurseForgeManifestParser.parse(hit.json, false);
        }

        List<String> missing = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        int checked = 0;
        int redownloaded = 0;
        List<ModpackFileEntry> files = desc.getFiles();
        int total = files == null ? 0 : files.size();
        if (total == 0) {
            status(sink, "索引中无在线文件条目", 1.0);
            return new RepairReport(versionId, 0, 0, List.of(), List.of());
        }

        int index = 0;
        for (ModpackFileEntry file : files) {
            index++;
            if (file.isClientOptional()) {
                continue;
            }
            checked++;
            String label = file.getPath() == null ? ("file#" + index) : file.getPath();
            status(sink, "校验 " + index + "/" + total + ": " + label,
                    index / (double) total);
            try {
                Path dest = resolveDest(layout, file.getPath());
                boolean needsDownload = !isFileOk(dest, file);
                if (!needsDownload) {
                    continue;
                }
                Files.createDirectories(dest.getParent());
                boolean ok = false;
                Exception last = null;
                for (int attempt = 1; attempt <= MAX_RETRIES && !ok; attempt++) {
                    try {
                        AtomicLong lastBytes = new AtomicLong(0);
                        java.util.function.BiConsumer<Long, Long> progress = (done, tot) -> {
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
                if (ok && isFileOk(dest, file)) {
                    redownloaded++;
                    fixed.add(label);
                } else {
                    String msg = label + (last == null ? "" : (" — " + last.getMessage()));
                    missing.add(msg);
                }
            } catch (Exception e) {
                missing.add(label + " — " + e.getMessage());
            }
        }

        if (sink != null) {
            sink.complete("修复完成：重下 " + redownloaded + "，缺失 " + missing.size());
        }
        LOG.info("Modpack repair {}: checked={} fixed={} missing={}",
                versionId, checked, redownloaded, missing.size());
        return new RepairReport(versionId, checked, redownloaded, List.copyOf(missing), List.copyOf(fixed));
    }

    private static boolean isFileOk(Path dest, ModpackFileEntry file) {
        if (!Files.isRegularFile(dest)) {
            return false;
        }
        try {
            if (file.getFileSize() > 0 && Files.size(dest) != file.getFileSize()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        if (Validation.isNonBlank(file.getSha1())) {
            return HashUtil.matchesSha1(dest, file.getSha1());
        }
        return true;
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

    private static IndexHit findIndex(MinecraftLayout layout, String versionId) {
        Path instance = layout.instanceRoot();
        Path versionDir = AppPaths.versionsDir().resolve(versionId);
        Path mods = layout.resolve(DirectoryKind.MODS);

        Path[] roots = {
                instance,
                versionDir,
                instance.resolve("overrides"),
                versionDir.resolve("overrides"),
                mods.getParent() == null ? instance : mods.getParent()
        };
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            Path mr = root.resolve("modrinth.index.json");
            if (Files.isRegularFile(mr)) {
                try {
                    return new IndexHit(Files.readString(mr, StandardCharsets.UTF_8), true);
                } catch (Exception e) {
                    LOG.debug("Failed reading {}: {}", mr, e.toString());
                }
            }
            Path cf = root.resolve("manifest.json");
            if (Files.isRegularFile(cf)) {
                try {
                    return new IndexHit(Files.readString(cf, StandardCharsets.UTF_8), false);
                } catch (Exception e) {
                    LOG.debug("Failed reading {}: {}", cf, e.toString());
                }
            }
        }
        return null;
    }

    private static void status(DownloadProgressSink sink, String msg, double p) {
        if (sink != null) {
            sink.status(msg, p);
        }
    }

    private record IndexHit(String json, boolean modrinth) {
    }
}
