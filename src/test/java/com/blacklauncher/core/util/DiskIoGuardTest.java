package com.blacklauncher.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DiskIoGuardTest {

    @TempDir
    Path temp;

    @Test
    void parallelWriteProbesDoNotFail() throws Exception {
        Path dir = temp.resolve("libs").resolve("org").resolve("lwjgl");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                jobs.add(() -> {
                    DiskIoGuard.ensureWritableDirectory(dir);
                    DiskIoGuard.ensureSpaceForDownload(dir.resolve("file-" + Thread.currentThread().getId() + ".jar"), 1024);
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(jobs);
            for (Future<Void> f : futures) {
                assertDoesNotThrow(() -> f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
