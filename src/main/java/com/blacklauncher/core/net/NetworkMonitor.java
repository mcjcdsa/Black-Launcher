package com.blacklauncher.core.net;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Built-in network status monitor. Downloads waiting for connectivity resume when online.
 */
public final class NetworkMonitor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkMonitor.class);

    private static final NetworkMonitor INSTANCE = new NetworkMonitor();

    private final AtomicBoolean online = new AtomicBoolean(true);
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private final OkHttpClient probeClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(4))
            .readTimeout(Duration.ofSeconds(4))
            .callTimeout(Duration.ofSeconds(6))
            .retryOnConnectionFailure(false)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "network-monitor");
        t.setDaemon(true);
        return t;
    });

    private final Object awaitLock = new Object();
    private volatile boolean started;

    private NetworkMonitor() {
    }

    public static NetworkMonitor get() {
        INSTANCE.ensureStarted();
        return INSTANCE;
    }

    private synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        probeAndPublish();
        scheduler.scheduleWithFixedDelay(this::probeAndPublish, 3, 3, TimeUnit.SECONDS);
    }

    public boolean isOnline() {
        return online.get();
    }

    public void addListener(Consumer<Boolean> listener) {
        if (listener != null) {
            listeners.add(listener);
            try {
                listener.accept(online.get());
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    public void removeListener(Consumer<Boolean> listener) {
        listeners.remove(listener);
    }

    /**
     * Blocks until online (or control cancelled / user-paused check via control).
     */
    public void awaitOnline(DownloadControl control) throws DownloadCancelledException {
        while (!isOnline()) {
            if (control != null && control.isCancelled()) {
                throw new DownloadCancelledException();
            }
            if (control != null && control.isUserPaused()) {
                // Return so DownloadControl can handle user pause separately.
                return;
            }
            synchronized (awaitLock) {
                try {
                    awaitLock.wait(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DownloadCancelledException();
                }
            }
        }
    }

    private void probeAndPublish() {
        boolean now = probe();
        boolean prev = online.getAndSet(now);
        if (prev != now) {
            LOG.info("Network status changed: {} -> {}", prev ? "online" : "offline", now ? "online" : "offline");
            for (Consumer<Boolean> listener : listeners) {
                try {
                    listener.accept(now);
                } catch (Exception e) {
                    LOG.warn("Network listener failed", e);
                }
            }
            synchronized (awaitLock) {
                awaitLock.notifyAll();
            }
        }
    }

    private boolean probe() {
        // Fast local check first.
        try {
            InetAddress.getByName("www.baidu.com");
        } catch (Exception e) {
            try {
                InetAddress.getByName("dns.google");
            } catch (Exception e2) {
                LOG.debug("DNS probe failed", e2);
                return false;
            }
        }
        // Lightweight HTTPS probes (mirror + official).
        String[] urls = {
                "https://bmclapi2.bangbang93.com",
                "https://launchermeta.mojang.com",
                "https://www.msftconnecttest.com/connecttest.txt"
        };
        for (String url : urls) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "BlackLauncher/1.0.0-netmon")
                    .get()
                    .build();
            try (Response response = probeClient.newCall(request).execute()) {
                int code = response.code();
                if (code > 0 && code < 600) {
                    return true;
                }
            } catch (Exception e) {
                LOG.debug("HTTP probe failed for {}", url);
            }
        }
        return false;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
