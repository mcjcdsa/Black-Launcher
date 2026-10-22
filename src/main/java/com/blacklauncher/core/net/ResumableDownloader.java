package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.DiskIoGuard;
import com.blacklauncher.core.util.HashUtil;
import com.blacklauncher.core.util.Validation;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * HTTP downloader with Range resume, disk checks, and network-aware auto-retry.
 * <p>
 * Network link failures wait for {@link NetworkMonitor} and resume from .part.
 * User pause / business errors (404, checksum, etc.) are not auto-retried.
 */
public class ResumableDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(ResumableDownloader.class);
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_NETWORK_RETRIES = 200;

    private final OkHttpClient client;
    private final DownloadSource source;
    private final NetworkMonitor networkMonitor;
    private volatile DownloadControl control;

    public ResumableDownloader(OkHttpClient client) {
        this(client, DownloadSource.BMCLAPI, NetworkMonitor.get());
    }

    public ResumableDownloader(OkHttpClient client, DownloadSource source) {
        this(client, source, NetworkMonitor.get());
    }

    public ResumableDownloader(OkHttpClient client, DownloadSource source, NetworkMonitor networkMonitor) {
        this.client = Objects.requireNonNull(client, "client");
        this.source = source == null ? DownloadSource.BMCLAPI : source;
        this.networkMonitor = networkMonitor == null ? NetworkMonitor.get() : networkMonitor;
    }

    public void setControl(DownloadControl control) {
        this.control = control;
    }

    public DownloadControl control() {
        return control;
    }

    public void download(String url, Path target, String expectedSha1, BiConsumer<Long, Long> progress)
            throws LauncherException {
        download(url, target, expectedSha1, -1L, progress);
    }

    public void download(String url, Path target, String expectedSha1, long expectedSize,
                         BiConsumer<Long, Long> progress) throws LauncherException {
        if (!Validation.isNonBlank(url)) {
            throw new LauncherException("下载地址为空");
        }
        Objects.requireNonNull(target, "target");
        String resolvedUrl = DownloadMirror.rewrite(url, source);

        try {
            DiskIoGuard.ensureSpaceForDownload(target, expectedSize);
            if (alreadyComplete(target, expectedSha1, expectedSize)) {
                return;
            }

            Path partial = target.resolveSibling(target.getFileName().toString() + ".part");
            int networkAttempts = 0;
            String[] candidates = buildCandidates(url, resolvedUrl);

            while (true) {
                awaitIfNeeded();
                long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
                Exception lastError = null;

                boolean success = false;
                for (String candidate : candidates) {
                    try {
                        awaitIfNeeded();
                        downloadOnce(candidate, target, partial, existing, expectedSha1, expectedSize, progress);
                        success = true;
                        break;
                    } catch (UserPausedException | DownloadCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        lastError = e;
                        LOG.warn("Download attempt failed via {}: {}", candidate, e.toString());
                        Integer http = NetworkErrorClassifier.extractHttpCode(
                                e.getMessage() == null && e.getCause() != null
                                        ? e.getCause().getMessage() : e.getMessage());
                        if (http != null && NetworkErrorClassifier.isStaleRangeHttp(http)) {
                            Files.deleteIfExists(partial);
                            existing = 0;
                        } else {
                            existing = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
                        }
                        // Only abort immediately on true business errors (404/sha1/…).
                        // Transient HTTP (522/503/416) must fall through to wait-retry.
                        if (NetworkErrors.isBusinessFailure(e) && !NetworkErrors.isNetworkFailure(e)) {
                            if (e instanceof LauncherException le) {
                                throw le;
                            }
                            throw new LauncherException("下载失败: " + e.getMessage(), e);
                        }
                    }
                }

                if (success) {
                    return;
                }

                if (lastError == null) {
                    throw new LauncherException("下载失败: " + resolvedUrl);
                }
                if (!NetworkErrors.isNetworkFailure(lastError)) {
                    if (lastError instanceof LauncherException le) {
                        throw le;
                    }
                    throw new LauncherException("下载失败: " + lastError.getMessage(), lastError);
                }

                networkAttempts++;
                if (networkAttempts > MAX_NETWORK_RETRIES) {
                    throw new LauncherException("网络异常次数过多，已停止自动重试: " + lastError.getMessage(), lastError);
                }
                LOG.info("Network error, entering wait-retry queue ({}/{}): {}",
                        networkAttempts, MAX_NETWORK_RETRIES, lastError.toString());
                if (control != null) {
                    control.markWaitingNetwork();
                }
                // Even if general internet is up, mirror 522 needs backoff + official fallback retry.
                networkMonitor.awaitOnline(control);
                awaitIfNeeded();
                sleepBackoff(networkAttempts);
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Download failed: {}", resolvedUrl, e);
            throw new LauncherException("下载失败: " + e.getMessage(), e);
        }
    }

    private void awaitIfNeeded() throws LauncherException {
        DownloadControl c = control;
        if (c != null) {
            // Blocks through user-pause and network wait; only cancel aborts.
            c.awaitRunnable(networkMonitor);
            if (c.isCancelled()) {
                throw new DownloadCancelledException();
            }
        } else if (!networkMonitor.isOnline()) {
            networkMonitor.awaitOnline(null);
        }
    }

    private String[] buildCandidates(String originalUrl, String mirroredUrl) {
        if (source == DownloadSource.OFFICIAL) {
            return new String[]{originalUrl};
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (Validation.isNonBlank(mirroredUrl)) {
            set.add(mirroredUrl);
        }
        if (Validation.isNonBlank(originalUrl)) {
            set.add(originalUrl);
        }
        // If caller already passed a BMCL URL, also try the official equivalent.
        String demirrored = DownloadMirror.toOfficial(originalUrl);
        if (Validation.isNonBlank(demirrored)) {
            set.add(demirrored);
        }
        String demirroredMirror = DownloadMirror.toOfficial(mirroredUrl);
        if (Validation.isNonBlank(demirroredMirror)) {
            set.add(demirroredMirror);
        }
        return set.toArray(String[]::new);
    }

    private void sleepBackoff(int attempt) {
        // Faster early retries for transient 522, then cap at 20s.
        long ms = Math.min(20_000L, 400L * attempt);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void downloadOnce(String url, Path target, Path partial, long existing,
                              String expectedSha1, long expectedSize,
                              BiConsumer<Long, Long> progress) throws Exception {
        downloadOnce(url, target, partial, existing, expectedSha1, expectedSize, progress, true);
    }

    private void downloadOnce(String url, Path target, Path partial, long existing,
                              String expectedSha1, long expectedSize,
                              BiConsumer<Long, Long> progress,
                              boolean allowRangeRestart) throws Exception {
        // Oversized / stale .part from a previous mirror or failed write → restart clean.
        if (existing > 0 && expectedSize > 0 && existing > expectedSize) {
            LOG.warn("Partial larger than expected ({} > {}), discarding {}", existing, expectedSize, partial);
            Files.deleteIfExists(partial);
            existing = 0;
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher/1.0.0")
                .get();
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=" + existing + "-");
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            int code = response.code();
            // 416 = Range not satisfiable (stale .part vs server size). Drop partial and full re-fetch.
            if (code == 416) {
                LOG.warn("HTTP 416 for {} (had {} bytes), clearing partial and restarting", url, existing);
                Files.deleteIfExists(partial);
                if (allowRangeRestart && existing > 0) {
                    downloadOnce(url, target, partial, 0, expectedSha1, expectedSize, progress, false);
                    return;
                }
                throw new LauncherException("下载失败 HTTP 416: " + url);
            }
            if (!response.isSuccessful() && code != 206) {
                throw new LauncherException("下载失败 HTTP " + code + ": " + url);
            }

            boolean resumed = code == 206;
            if (!resumed && existing > 0) {
                Files.deleteIfExists(partial);
                existing = 0;
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("下载响应体为空: " + url);
            }

            long total = existing;
            long contentLength = body.contentLength();
            if (contentLength >= 0) {
                total = existing + contentLength;
            } else if (response.header("Content-Range") != null) {
                String range = response.header("Content-Range");
                int slash = range.lastIndexOf('/');
                if (slash > 0) {
                    total = Long.parseLong(range.substring(slash + 1).trim());
                }
            } else if (expectedSize > 0) {
                total = expectedSize;
            }

            DiskIoGuard.ensureSpaceForDownload(target, total > existing ? total - existing : expectedSize);

            StandardOpenOption[] options = resumed
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

            long downloaded = existing;
            try (InputStream in = new BufferedInputStream(body.byteStream(), BUFFER_SIZE);
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial, options), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long lastReport = 0;
                while ((read = in.read(buffer)) != -1) {
                    awaitIfNeeded();
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (progress != null && (downloaded - lastReport >= BUFFER_SIZE || downloaded == total)) {
                        progress.accept(downloaded, total > 0 ? total : downloaded);
                        lastReport = downloaded;
                    }
                }
                out.flush();
            }

            if (expectedSize > 0 && Files.size(partial) != expectedSize) {
                Files.deleteIfExists(partial);
                throw new LauncherException("文件大小不匹配: " + target.getFileName());
            }

            if (Validation.isNonBlank(expectedSha1) && !HashUtil.matchesSha1(partial, expectedSha1)) {
                Files.deleteIfExists(partial);
                throw new LauncherException("SHA-1 校验失败: " + target.getFileName());
            }

            Files.deleteIfExists(target);
            Files.move(partial, target);
            if (progress != null) {
                long size = Files.size(target);
                progress.accept(size, size);
            }
        }
    }

    private boolean alreadyComplete(Path target, String expectedSha1, long expectedSize) throws Exception {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        long size = Files.size(target);
        if (expectedSize > 0 && size != expectedSize) {
            return false;
        }
        if (expectedSize > 0 && size == expectedSize && !Validation.isNonBlank(expectedSha1)) {
            return true;
        }
        if (expectedSize > 0 && size == expectedSize && Validation.isNonBlank(expectedSha1)) {
            String name = target.getFileName().toString();
            if (name.equalsIgnoreCase(expectedSha1)) {
                return true;
            }
            return HashUtil.matchesSha1(target, expectedSha1);
        }
        if (Validation.isNonBlank(expectedSha1)) {
            return HashUtil.matchesSha1(target, expectedSha1);
        }
        return size > 0;
    }
}
