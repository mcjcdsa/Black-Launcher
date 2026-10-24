package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/**
 * Concurrent downloads with shared {@link DownloadControl} / network wait-retry.
 */
public class ParallelDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(ParallelDownloader.class);

    private final ResumableDownloader downloader;

    public ParallelDownloader(ResumableDownloader downloader) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
    }

    public void downloadAll(List<DownloadTask> tasks,
                            int concurrency,
                            AtomicBoolean cancelled,
                            BiConsumer<Integer, Integer> progress) throws LauncherException {
        downloadAll(tasks, concurrency, cancelled, progress, null, null);
    }

    public void downloadAll(List<DownloadTask> tasks,
                            int concurrency,
                            AtomicBoolean cancelled,
                            BiConsumer<Integer, Integer> progress,
                            LongConsumer bytesListener) throws LauncherException {
        downloadAll(tasks, concurrency, cancelled, progress, bytesListener, null);
    }

    /**
     * @param fileProgress optional (fileName, doneBytes, totalBytes) for the active dependency file
     */
    public void downloadAll(List<DownloadTask> tasks,
                            int concurrency,
                            AtomicBoolean cancelled,
                            BiConsumer<Integer, Integer> progress,
                            LongConsumer bytesListener,
                            FileProgressListener fileProgress) throws LauncherException {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Math.min(concurrency, tasks.size()));
        AtomicInteger completed = new AtomicInteger(0);
        AtomicReference<Exception> firstError = new AtomicReference<>();
        int total = tasks.size();
        DownloadControl control = downloader.control();

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "dl-parallel");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (DownloadTask task : tasks) {
                futures.add(pool.submit(() -> {
                    if (cancelled != null && cancelled.get()) {
                        return;
                    }
                    if (control != null && control.isCancelled()) {
                        return;
                    }
                    Exception business = firstError.get();
                    if (business != null && NetworkErrors.isBusinessFailure(business)) {
                        return;
                    }
                    try {
                        String fileName = task.target() == null ? ""
                                : task.target().getFileName().toString();
                        AtomicLong lastReported = new AtomicLong(0);
                        BiConsumer<Long, Long> byteProgress = (done, fileTotal) -> {
                            if (bytesListener != null) {
                                long prev = lastReported.getAndSet(done);
                                long delta = done - prev;
                                if (delta > 0) {
                                    bytesListener.accept(delta);
                                }
                            }
                            if (fileProgress != null) {
                                fileProgress.onFile(fileName, done, fileTotal == null ? -1L : fileTotal);
                            }
                        };
                        try {
                            downloader.download(
                                    task.url(),
                                    task.target(),
                                    task.expectedSha1(),
                                    task.expectedSize(),
                                    byteProgress
                            );
                        } catch (Exception first) {
                            // Mirror 522 / transient failures: one more wait + retry before failing batch.
                            if (!NetworkErrors.isNetworkFailure(first)
                                    || (cancelled != null && cancelled.get())
                                    || (control != null && control.isCancelled())) {
                                throw first;
                            }
                            LOG.warn("Network failure on {}, waiting then retrying once: {}",
                                    task.url(), first.toString());
                            if (control != null) {
                                control.markWaitingNetwork();
                            }
                            NetworkMonitor.get().awaitOnline(control);
                            if (control != null) {
                                control.awaitRunnable(NetworkMonitor.get());
                            }
                            lastReported.set(0);
                            downloader.download(
                                    task.url(),
                                    task.target(),
                                    task.expectedSha1(),
                                    task.expectedSize(),
                                    byteProgress
                            );
                            if (control != null) {
                                control.markRunning();
                            }
                        }
                        int doneCount = completed.incrementAndGet();
                        if (progress != null) {
                            progress.accept(doneCount, total);
                        }
                    } catch (DownloadCancelledException e) {
                        firstError.compareAndSet(null, e);
                    } catch (UserPausedException e) {
                        LOG.debug("Worker saw user pause: {}", task.url());
                    } catch (Exception e) {
                        LOG.warn("Parallel download failed: {}", task.url(), e);
                        firstError.compareAndSet(null, e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    firstError.compareAndSet(null, e);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if ((cancelled != null && cancelled.get()) || (control != null && control.isCancelled())) {
            throw new DownloadCancelledException();
        }
        Exception error = firstError.get();
        if (error instanceof UserPausedException) {
            // Should not abort a finished batch; pause only affects in-flight waits.
            error = null;
        }
        if (error != null) {
            if (error instanceof LauncherException le) {
                throw le;
            }
            throw new LauncherException("并行下载失败: " + error.getMessage(), error);
        }
    }

    @FunctionalInterface
    public interface FileProgressListener {
        void onFile(String fileName, long doneBytes, long totalBytes);
    }
}
