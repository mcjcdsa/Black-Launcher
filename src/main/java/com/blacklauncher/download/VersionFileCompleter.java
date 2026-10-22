package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.LaunchCommandBuilder;
import com.blacklauncher.model.VersionJson;
import com.blacklauncher.model.VersionManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a version and downloads missing jars / libraries (§7 补全文件).
 */
public final class VersionFileCompleter {

    private static final Logger LOG = LoggerFactory.getLogger(VersionFileCompleter.class);

    public record Result(boolean alreadyHealthy, int fixedCount, List<String> details) {
    }

    private final AppConfig config;
    private final Database database;
    private final VersionRepairService repairService = new VersionRepairService();
    private final LaunchCommandBuilder commandBuilder = new LaunchCommandBuilder();

    public VersionFileCompleter(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
    }

    public Result complete(String versionId, DownloadProgressSink sink) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        List<String> details = new ArrayList<>();
        int fixed = 0;

        status(sink, "检测版本完整性…", 0.05);
        VersionRepairService.RepairReport report = repairService.inspect(versionId);
        if (!report.healthy()) {
            details.addAll(report.issues());
            status(sink, "准备修复损坏文件…", 0.1);
            repairService.prepareRepair(versionId);
        }

        Path json = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(json)) {
            // Try re-download vanilla/profile from manifest if id looks like a MC version.
            status(sink, "重新下载版本配置…", 0.2);
            if (tryRedownloadVersion(versionId, sink)) {
                fixed++;
                details.add("已重新下载版本 " + versionId);
            } else {
                throw new LauncherException("缺少版本 JSON，且无法从清单自动补全: " + versionId
                        + "。请前往「下载」页重新安装该版本。");
            }
        }

        status(sink, "检查依赖库…", 0.35);
        VersionJson merged = commandBuilder.loadMerged(versionId);
        List<Path> missing = MavenLibraryResolver.listMissingLibraries(merged);
        if (!missing.isEmpty()) {
            status(sink, "补全缺失依赖 " + missing.size() + " 个…", 0.4);
            ResumableDownloader downloader = new ResumableDownloader(HttpClientFactory.create(config));
            MavenLibraryResolver.downloadLibraries(merged, downloader, true);
            List<Path> still = MavenLibraryResolver.listMissingLibraries(merged);
            int recovered = missing.size() - still.size();
            fixed += Math.max(recovered, 0);
            details.add("依赖库：缺失 " + missing.size() + "，已补全 " + recovered);
            if (!still.isEmpty()) {
                details.add("仍缺失 " + still.size() + " 个库（例如 " + still.get(0).getFileName() + "）");
            }
        } else {
            details.add("依赖库完整");
        }

        Path jar = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".jar");
        if (!Files.isRegularFile(jar)) {
            String parent = merged.getInheritsFrom();
            if (Validation.isNonBlank(parent)) {
                Path parentJar = AppPaths.versionsDir().resolve(parent).resolve(parent + ".jar");
                if (Files.isRegularFile(parentJar)) {
                    try {
                        Files.copy(parentJar, jar);
                        fixed++;
                        details.add("已从父版本复制客户端 jar");
                    } catch (Exception e) {
                        LOG.warn("Copy parent jar failed", e);
                    }
                } else {
                    status(sink, "补全父版本 " + parent + "…", 0.7);
                    if (tryRedownloadVersion(parent, sink)) {
                        fixed++;
                        details.add("已补全父版本 " + parent);
                        if (Files.isRegularFile(AppPaths.versionsDir().resolve(parent).resolve(parent + ".jar"))
                                && !Files.isRegularFile(jar)) {
                            try {
                                Files.copy(AppPaths.versionsDir().resolve(parent).resolve(parent + ".jar"), jar);
                                fixed++;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            } else if (tryRedownloadVersion(versionId, sink)) {
                fixed++;
                details.add("已重新下载客户端");
            }
        }

        // Re-inspect
        VersionRepairService.RepairReport after = repairService.inspect(versionId);
        List<Path> missingAfter = MavenLibraryResolver.listMissingLibraries(commandBuilder.loadMerged(versionId));
        boolean healthy = after.healthy() && missingAfter.isEmpty();
        if (sink != null) {
            sink.complete(healthy ? "文件完整" : "补全结束（仍有问题）");
        }
        if (healthy && fixed == 0 && report.healthy()) {
            return new Result(true, 0, List.of("文件完整无需修复"));
        }
        return new Result(healthy && fixed == 0, fixed, List.copyOf(details));
    }

    private boolean tryRedownloadVersion(String versionId, DownloadProgressSink sink) {
        try {
            VersionManifestService manifestService = new VersionManifestService(config);
            VersionManifest manifest = manifestService.fetch();
            VersionManifest.VersionEntry entry = null;
            if (manifest.getVersions() != null) {
                for (VersionManifest.VersionEntry e : manifest.getVersions()) {
                    if (versionId.equals(e.getId())) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) {
                return false;
            }
            GameDownloadService game = new GameDownloadService(config, database);
            game.downloadVersion(entry, sink);
            return true;
        } catch (Exception e) {
            LOG.warn("Redownload {} failed: {}", versionId, e.toString());
            return false;
        }
    }

    private static void status(DownloadProgressSink sink, String msg, double p) {
        if (sink != null) {
            sink.status(msg, p);
        }
    }
}
