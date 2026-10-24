package com.blacklauncher.memory;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Periodically probes system + optional MC process working set + launcher heap.
 * Listeners are invoked on the JavaFX application thread when available.
 */
public final class MemoryMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryMonitor.class);

    public record Sample(
            long totalBytes,
            long freeBytes,
            long usedBytes,
            long launcherUsedBytes,
            long mcWorkingSetBytes,
            long mcPid
    ) {
    }

    private final CopyOnWriteArrayList<Consumer<Sample>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private volatile long trackedMcPid = -1;

    public void addListener(Consumer<Sample> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<Sample> listener) {
        listeners.remove(listener);
    }

    public void setTrackedMcPid(long pid) {
        this.trackedMcPid = pid;
    }

    public synchronized void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "memory-monitor");
                t.setDaemon(true);
                return t;
            });
        }
        task = scheduler.scheduleAtFixedRate(this::tick, 0, 2, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tick() {
        try {
            SystemMemoryProbe.Snapshot snap = SystemMemoryProbe.probe();
            Runtime rt = Runtime.getRuntime();
            long launcherUsed = rt.totalMemory() - rt.freeMemory();
            long mcWs = 0L;
            long pid = trackedMcPid;
            if (pid > 0) {
                mcWs = queryProcessWorkingSet(pid);
            }
            Sample sample = new Sample(
                    snap.totalBytes(), snap.freeBytes(), snap.usedBytes(),
                    launcherUsed, mcWs, pid);
            for (Consumer<Sample> listener : listeners) {
                dispatchFxSafe(listener, sample);
            }
        } catch (Throwable t) {
            LOG.debug("MemoryMonitor tick failed: {}", t.getMessage());
        }
    }

    private static void dispatchFxSafe(Consumer<Sample> listener, Sample sample) {
        try {
            if (Platform.isFxApplicationThread()) {
                listener.accept(sample);
            } else {
                Platform.runLater(() -> {
                    try {
                        listener.accept(sample);
                    } catch (Throwable t) {
                        LOG.debug("MemoryMonitor listener error: {}", t.getMessage());
                    }
                });
            }
        } catch (IllegalStateException noFx) {
            // FX toolkit not started — call directly
            try {
                listener.accept(sample);
            } catch (Throwable t) {
                LOG.debug("MemoryMonitor listener error: {}", t.getMessage());
            }
        }
    }

    private static long queryProcessWorkingSet(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return 0L;
        }
        try {
            return WinProcessMemory.workingSetBytes(pid);
        } catch (Throwable t) {
            LOG.debug("GetProcessMemoryInfo failed: {}", t.getMessage());
            return 0L;
        }
    }
}
