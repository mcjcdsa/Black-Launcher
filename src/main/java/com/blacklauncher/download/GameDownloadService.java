package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadControl;
import com.blacklauncher.core.net.DownloadMirror;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.DownloadTask;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ParallelDownloader;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.GameOptionsConfigurator;
import com.blacklauncher.launch.NativesExtractor;
import com.blacklauncher.model.VersionJson;
import com.blacklauncher.model.VersionManifest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads a Minecraft version with libraries, assets and natives.
 * Uses mirror rewrite + parallel file downloads for speed.
 */
public class GameDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(GameDownloadService.class);

    private final AppConfig config;
    private final OkHttpClient client;
    private final ResumableDownloader downloader;
    private final ParallelDownloader parallelDownloader;
    private final DownloadSource source;
    private final int threads;
    private final Database database;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final DownloadControl downloadControl = new DownloadControl();

    public GameDownloadService(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.source = DownloadSource.fromId(config == null ? null : config.getDownloadSource());
        this.threads = config == null ? 32 : Math.max(4, Math.min(config.getDownloadThreads(), 128));
        this.client = HttpClientFactory.create(config);
        this.downloader = new ResumableDownloader(client, source);
        this.downloader.setControl(downloadControl);
        this.parallelDownloader = new ParallelDownloader(downloader);
    }

    public DownloadControl downloadControl() {
        return downloadControl;
    }

    public void cancel() {
        cancelled.set(true);
        downloadControl.cancel();
    }

    public void pauseByUser() {
        downloadControl.pauseByUser();
    }

    public void resumeByUser() {
        downloadControl.resumeByUser();
    }

    public void downloadVersion(VersionManifest.VersionEntry entry, DownloadProgressSink sink)
            throws LauncherException {
        Validation.requireSafeVersionId(entry.getId());
        cancelled.set(false);
        report(sink, "获取版本元数据（" + source.displayName() + "）...", 0.02);

        String metaUrl = DownloadMirror.rewrite(entry.getUrl(), source);
        VersionJson versionJson = fetchVersionJson(metaUrl, entry.getUrl());
        Path versionDir = AppPaths.versionsDir().resolve(entry.getId());
        try {
            Files.createDirectories(versionDir);
            Path versionFile = versionDir.resolve(entry.getId() + ".json");
            Files.writeString(versionFile, JsonUtil.toJsonPretty(versionJson), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new LauncherException("写入版本 JSON 失败", e);
        }

        downloadClientJar(versionJson, versionDir, sink);
        downloadLibraries(versionJson, sink);
        downloadAssets(versionJson, sink);
        extractNatives(versionJson, entry.getId(), sink);

        try {
            database.upsertInstalledVersion(entry.getId(), entry.getType() == null ? "release" : entry.getType());
        } catch (Exception e) {
            throw new LauncherException("记录已安装版本失败", e);
        }

        report(sink, "配置游戏语言...", 0.98);
        GameOptionsConfigurator.applyIfEnabled(config, entry.getId());
        report(sink, "原版文件就绪", 1.0);
    }

    private VersionJson fetchVersionJson(String primaryUrl, String fallbackUrl) throws LauncherException {
        Exception last = null;
        for (String url : unique(primaryUrl, fallbackUrl)) {
            try {
                return fetchVersionJsonOnce(url);
            } catch (Exception e) {
                last = e;
                LOG.warn("Version meta fetch failed via {}", url, e);
            }
        }
        throw new LauncherException("获取版本 JSON 失败: " + (last == null ? "" : last.getMessage()), last);
    }

    private VersionJson fetchVersionJsonOnce(String url) throws LauncherException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher/1.0.0")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("获取版本 JSON 失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("版本 JSON 为空");
            }
            VersionJson json = JsonUtil.fromJson(body.string(), VersionJson.class);
            if (json == null || json.getId() == null) {
                throw new LauncherException("版本 JSON 解析失败");
            }
            return json;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("获取版本 JSON 失败: " + e.getMessage(), e);
        }
    }

    private void downloadClientJar(VersionJson versionJson, Path versionDir, DownloadProgressSink sink)
            throws LauncherException {
        ensureNotCancelled();
        if (versionJson.getDownloads() == null || versionJson.getDownloads().getClient() == null) {
            throw new LauncherException("版本缺少 client 下载信息");
        }
        VersionJson.Artifact clientArt = versionJson.getDownloads().getClient();
        Path jar = versionDir.resolve(versionJson.getId() + ".jar");
        report(sink, "下载客户端...", 0.08);
        AtomicLong last = new AtomicLong(0);
        downloader.download(clientArt.getUrl(), jar, clientArt.getSha1(), clientArt.getSize(),
                (done, total) -> {
                    long prev = last.getAndSet(done);
                    long delta = done - prev;
                    if (delta > 0 && sink != null) {
                        sink.addBytes(delta);
                    }
                    double p = total > 0 ? 0.08 + 0.12 * (done / (double) total) : 0.1;
                    report(sink, "下载客户端...", p);
                });
    }

    private void downloadLibraries(VersionJson versionJson, DownloadProgressSink sink)
            throws LauncherException {
        var libraries = versionJson.getLibraries();
        if (libraries == null || libraries.isEmpty()) {
            return;
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (VersionJson.Library library : libraries) {
            ensureNotCancelled();
            if (!OsRules.allows(library.getRules())) {
                continue;
            }
            if (library.getDownloads() != null && library.getDownloads().getArtifact() != null
                    && Validation.isNonBlank(library.getDownloads().getArtifact().getUrl())) {
                VersionJson.Artifact artifact = library.getDownloads().getArtifact();
                String path = artifact.getPath() != null
                        ? artifact.getPath().replace('\\', '/')
                        : MavenLibraryResolver.mavenPath(library.getName());
                Path target = AppPaths.librariesDir().resolve(path);
                tasks.add(new DownloadTask(artifact.getUrl(), target, artifact.getSha1(), artifact.getSize()));
            } else if (Validation.isNonBlank(library.getName())) {
                String path = MavenLibraryResolver.mavenPath(library.getName());
                Path target = AppPaths.librariesDir().resolve(path);
                String base = DownloadMirror.mavenBase(source, MavenLibraryResolver.resolveMavenBase(library));
                tasks.add(new DownloadTask(base + MavenLibraryResolver.encodeMavenUrlPath(path), target, null));
            }

            if (library.getNatives() != null && library.getDownloads() != null
                    && library.getDownloads().getClassifiers() != null) {
                String raw = library.getNatives().get(OsRules.currentOsName());
                if (raw != null) {
                    String classifier = OsRules.resolveClassifier(raw);
                    VersionJson.Artifact nativeArt = library.getDownloads().getClassifiers().get(classifier);
                    if (nativeArt == null) {
                        nativeArt = library.getDownloads().getClassifiers().get(raw);
                    }
                    if (nativeArt != null && nativeArt.getPath() != null
                            && Validation.isNonBlank(nativeArt.getUrl())) {
                        Path target = AppPaths.librariesDir().resolve(nativeArt.getPath().replace('\\', '/'));
                        tasks.add(new DownloadTask(nativeArt.getUrl(), target, nativeArt.getSha1(), nativeArt.getSize()));
                    }
                }
            }
        }

        report(sink, "并行下载依赖库（" + threads + " 线程）...", 0.22);
        parallelDownloader.downloadAll(tasks, threads, cancelled, (done, total) -> {
            double p = 0.22 + 0.30 * (done / (double) Math.max(total, 1));
            report(sink, "依赖库 " + done + "/" + total, p);
        }, bytes -> {
            if (sink != null) {
                sink.addBytes(bytes);
            }
        });
    }

    private void downloadAssets(VersionJson versionJson, DownloadProgressSink sink)
            throws LauncherException {
        ensureNotCancelled();
        VersionJson.AssetIndex assetIndex = versionJson.getAssetIndex();
        if (assetIndex == null || assetIndex.getUrl() == null) {
            report(sink, "无资源索引，跳过 assets", 0.70);
            return;
        }

        Path indexesDir = AppPaths.assetsDir().resolve("indexes");
        Path objectsDir = AppPaths.assetsDir().resolve("objects");
        Path indexFile = indexesDir.resolve(assetIndex.getId() + ".json");
        report(sink, "下载资源索引...", 0.54);
        AtomicLong indexLast = new AtomicLong(0);
        downloader.download(assetIndex.getUrl(), indexFile, assetIndex.getSha1(), assetIndex.getSize(),
                (done, total) -> {
                    long prev = indexLast.getAndSet(done);
                    long delta = done - prev;
                    if (delta > 0 && sink != null) {
                        sink.addBytes(delta);
                    }
                });

        JsonObject root;
        try {
            root = JsonUtil.parse(Files.readString(indexFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new LauncherException("解析资源索引失败", e);
        }
        JsonObject objects = root.getAsJsonObject("objects");
        if (objects == null || objects.size() == 0) {
            return;
        }

        // Always use official asset URLs here; ResumableDownloader rewrites to BMCL
        // and falls back to official on mirror 5xx (e.g. HTTP 522).
        String officialAssetsBase = DownloadMirror.officialAssetsBase();
        List<DownloadTask> tasks = new ArrayList<>(objects.size());
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            long size = obj.has("size") ? obj.get("size").getAsLong() : -1L;
            String prefix = hash.substring(0, 2);
            Path target = objectsDir.resolve(prefix).resolve(hash);
            String url = officialAssetsBase + prefix + "/" + hash;
            tasks.add(new DownloadTask(url, target, hash, size));
        }

        report(sink, "并行下载资源（" + threads + " 线程，共 " + tasks.size() + "）...", 0.58);
        parallelDownloader.downloadAll(tasks, threads, cancelled, (done, total) -> {
            double p = 0.58 + 0.32 * (done / (double) Math.max(total, 1));
            report(sink, "资源 " + done + "/" + total, p);
        }, bytes -> {
            if (sink != null) {
                sink.addBytes(bytes);
            }
        });
    }

    private void extractNatives(VersionJson versionJson, String versionId, DownloadProgressSink sink)
            throws LauncherException {
        ensureNotCancelled();
        report(sink, "解压 natives...", 0.92);
        NativesExtractor.extract(versionJson, versionId);
    }

    private void ensureNotCancelled() throws LauncherException {
        if (cancelled.get()) {
            throw new LauncherException("下载已取消");
        }
    }

    private void report(DownloadProgressSink sink, String message, double progress) {
        if (sink != null) {
            sink.status(message, Math.max(0, Math.min(1, progress)));
        }
    }

    private static List<String> unique(String... urls) {
        List<String> list = new ArrayList<>();
        for (String url : urls) {
            if (Validation.isNonBlank(url) && !list.contains(url)) {
                list.add(url);
            }
        }
        return list;
    }
}
