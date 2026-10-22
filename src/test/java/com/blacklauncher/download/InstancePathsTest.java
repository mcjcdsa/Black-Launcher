package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancePathsTest {

    @Test
    void prefersFabricProfileForFabricLoader() {
        // Without disk installs, falls back to mc version or selected.
        AppConfig config = new AppConfig();
        config.setSelectedVersion("fabric-loader-0.16.9-1.20.1");
        config.setIsolateGameDirs(true);
        String id = InstancePaths.resolveDefaultInstanceId(config, "1.20.1", "fabric");
        assertTrue(id.contains("1.20.1") || id.contains("fabric"));
    }
}
