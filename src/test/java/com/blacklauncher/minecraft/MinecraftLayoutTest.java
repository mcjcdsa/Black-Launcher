package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLayoutTest {

    @TempDir
    Path temp;

    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("user.home");
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        AppPaths.ensureDirectories();
    }

    @AfterEach
    void tearDown() {
        if (previousHome != null) {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void isolationMovesUserContentUnderInstances() {
        AppConfig config = new AppConfig();
        config.setIsolateGameDirs(true);
        MinecraftLayout layout = MinecraftLayout.forVersion(config, "1.20.1");
        assertTrue(layout.isolationEnabled());
        assertEquals(
                AppPaths.gameDir().resolve("instances").resolve("1.20.1").resolve("mods"),
                layout.resolve(DirectoryKind.MODS));
        assertEquals(AppPaths.assetsDir(), layout.resolve(DirectoryKind.ASSETS));
        assertTrue(DirectoryKind.ASSETS.systemManaged());
        assertTrue(DirectoryKind.MODS.userWritable());
    }

    @Test
    void sharedModeUsesGameRootForMods() {
        AppConfig config = new AppConfig();
        config.setIsolateGameDirs(false);
        MinecraftLayout layout = MinecraftLayout.forVersion(config, "1.20.1");
        assertEquals(AppPaths.gameDir().resolve("mods"), layout.resolve(DirectoryKind.MODS));
    }
}
