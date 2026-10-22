package com.blacklauncher.core.net;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Aggregates download status, percent and speed for UI display.
 */
public class DownloadProgressSink {

    private static final long WINDOW_NS = 2_000_000_000L;
    private static final long UI_MIN_INTERVAL_NS = 80_000_000L;

    private final Consumer<Snapshot> listener;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final Object lock = new Object();

    private volatile String message = "就绪";
    private volatile double progress;
    private long lastUiNs;

    public DownloadProgressSink(Consumer<Snapshot> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void reset(String message) {
        synchronized (lock) {
            samples.clear();
            totalBytes.set(0);
            this.message = message == null ? "" : message;
            this.progress = 0;
            lastUiNs = 0;
        }
        publish(true);
    }

    public void status(String message, double progress01) {
        this.message = message == null ? "" : message;
        this.progress = clamp(progress01);
        publish(false);
    }

    /** Report newly transferred bytes (incremental). */
    public void addBytes(long bytes) {
        if (bytes <= 0) {
            return;
        }
        totalBytes.addAndGet(bytes);
        long now = System.nanoTime();
        synchronized (lock) {
            samples.addLast(new Sample(now, bytes));
            prune(now);
        }
        publish(false);
    }

    public void complete(String message) {
        this.message = message == null ? "完成" : message;
        this.progress = 1.0;
        publish(true);
    }

    private void publish(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastUiNs < UI_MIN_INTERVAL_NS) {
            return;
        }
        lastUiNs = now;
        double speed = currentSpeed(now);
        Snapshot snap = new Snapshot(
                message,
                progress,
                speed,
                formatPercent(progress),
                formatSpeed(speed),
                totalBytes.get()
        );
        listener.accept(snap);
    }

    private double currentSpeed(long nowNs) {
        synchronized (lock) {
            prune(nowNs);
            if (samples.isEmpty()) {
                return 0;
            }
            long sum = 0;
            long oldest = samples.peekFirst().nanos;
            for (Sample s : samples) {
                sum += s.bytes;
            }
            double seconds = Math.max((nowNs - oldest) / 1_000_000_000.0, 0.2);
            return sum / seconds;
        }
    }

    private void prune(long nowNs) {
        while (!samples.isEmpty() && nowNs - samples.peekFirst().nanos > WINDOW_NS) {
            samples.removeFirst();
        }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0;
        }
        return Math.min(1.0, v);
    }

    public static String formatPercent(double progress01) {
        return String.format(Locale.ROOT, "%.1f%%", clamp(progress01) * 100.0);
    }

    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 B/s";
        }
        if (bytesPerSec < 1024) {
            return String.format(Locale.ROOT, "%.0f B/s", bytesPerSec);
        }
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB/s", bytesPerSec / 1024.0);
        }
        if (bytesPerSec < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0));
    }

    public record Snapshot(
            String message,
            double progress,
            double bytesPerSecond,
            String percentText,
            String speedText,
            long totalBytes
    ) {
    }

    private record Sample(long nanos, long bytes) {
    }
}
