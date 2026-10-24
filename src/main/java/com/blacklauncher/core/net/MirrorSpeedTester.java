package com.blacklauncher.core.net;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Quick latency probe for download mirrors (HEAD / short GET).
 */
public final class MirrorSpeedTester {

    private static final String OFFICIAL_MANIFEST =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String BMCL_MANIFEST =
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json";

    public record Result(DownloadSource source, long latencyMs, boolean ok, String detail) {
    }

    private final OkHttpClient client;

    public MirrorSpeedTester() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(3))
                .callTimeout(Duration.ofSeconds(3))
                .retryOnConnectionFailure(false)
                .build();
    }

    public List<Result> testAll() {
        List<Result> out = new ArrayList<>(2);
        out.add(testOne(DownloadSource.BMCLAPI, BMCL_MANIFEST));
        out.add(testOne(DownloadSource.OFFICIAL, OFFICIAL_MANIFEST));
        return out;
    }

    public DownloadSource recommendFastest() {
        return testAll().stream()
                .filter(Result::ok)
                .min(Comparator.comparingLong(Result::latencyMs))
                .map(Result::source)
                .orElse(DownloadSource.BMCLAPI);
    }

    private Result testOne(DownloadSource source, String url) {
        long start = System.nanoTime();
        try {
            Request head = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "BlackLauncher/1.0.0")
                    .head()
                    .build();
            try (Response response = client.newCall(head).execute()) {
                long ms = (System.nanoTime() - start) / 1_000_000L;
                if (response.isSuccessful() || response.code() == 405) {
                    return new Result(source, ms, true, "HTTP " + response.code());
                }
                // Some mirrors reject HEAD — fall back to GET (body discarded).
                if (response.code() == 403 || response.code() == 404 || response.code() >= 500) {
                    return tryGet(source, url, start);
                }
                return new Result(source, ms, false, "HTTP " + response.code());
            }
        } catch (Exception e) {
            return tryGet(source, url, start);
        }
    }

    private Result tryGet(DownloadSource source, String url, long startNanos) {
        try {
            Request get = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "BlackLauncher/1.0.0")
                    .get()
                    .build();
            try (Response response = client.newCall(get).execute()) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                if (response.body() != null) {
                    // Drain a small amount so the call completes, then discard.
                    response.body().source().request(8192);
                    response.body().close();
                }
                boolean ok = response.isSuccessful();
                return new Result(source, ms, ok, "GET HTTP " + response.code());
            }
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(source, ms, false, msg);
        }
    }
}
