package com.blacklauncher.core.net;

import com.blacklauncher.core.config.AppConfig;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds OkHttp clients tuned for high-concurrency game downloads.
 */
public final class HttpClientFactory {

    private static volatile OkHttpClient shared;
    private static volatile String sharedKey;

    private HttpClientFactory() {
    }

    public static OkHttpClient create(AppConfig config) {
        int threads = config == null ? 32 : Math.max(4, Math.min(config.getDownloadThreads(), 128));
        String proxyHost = config == null || config.getProxyHost() == null ? "" : config.getProxyHost().trim();
        int proxyPort = config == null ? 0 : config.getProxyPort();
        String key = threads + "|" + proxyHost + "|" + proxyPort;

        OkHttpClient current = shared;
        if (current != null && key.equals(sharedKey)) {
            return current;
        }

        synchronized (HttpClientFactory.class) {
            if (shared != null && key.equals(sharedKey)) {
                return shared;
            }
            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(Math.max(64, threads * 2));
            dispatcher.setMaxRequestsPerHost(Math.max(32, threads));

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .connectionPool(new ConnectionPool(Math.max(32, threads), 5, TimeUnit.MINUTES))
                    .connectTimeout(Duration.ofSeconds(15))
                    .readTimeout(Duration.ofSeconds(120))
                    .writeTimeout(Duration.ofSeconds(120))
                    .callTimeout(Duration.ofMinutes(45))
                    .retryOnConnectionFailure(true)
                    .pingInterval(30, TimeUnit.SECONDS);

            if (!proxyHost.isBlank() && proxyPort > 0) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                builder.proxy(proxy);
            }

            shared = builder.build();
            sharedKey = key;
            return shared;
        }
    }
}
