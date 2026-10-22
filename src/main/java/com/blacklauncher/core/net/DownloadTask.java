package com.blacklauncher.core.net;

import java.nio.file.Path;

/**
 * One file download unit for the parallel engine.
 */
public record DownloadTask(String url, Path target, String expectedSha1, long expectedSize) {

    public DownloadTask(String url, Path target, String expectedSha1) {
        this(url, target, expectedSha1, -1L);
    }
}
