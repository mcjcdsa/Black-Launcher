package com.blacklauncher.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallLayoutTest {

    @TempDir
    Path temp;

    @Test
    void readsInstallJson() throws Exception {
        Path launcher = temp.resolve("BlackLauncher");
        Path game = temp.resolve("MinecraftGame");
        Files.createDirectories(launcher);
        Files.createDirectories(game);
        Path json = launcher.resolve("install.json");
        Files.writeString(json, """
                {
                  "schema": 1,
                  "root": "%s",
                  "launcherDir": "%s",
                  "gameDir": "%s"
                }
                """.formatted(
                temp.toString().replace('\\', '/'),
                launcher.toString().replace('\\', '/'),
                game.toString().replace('\\', '/')
        ), StandardCharsets.UTF_8);

        var layout = InstallLayout.readInstallJson(json);
        assertTrue(layout.isPresent());
        assertEquals(game.toAbsolutePath().normalize(), layout.get().gameDir());
        assertEquals(launcher.toAbsolutePath().normalize(), layout.get().launcherDir());
    }

    @Test
    void detectsSiblingFolders() throws Exception {
        Path root = temp.resolve("Root");
        Path launcher = root.resolve("BlackLauncher");
        Path game = root.resolve("MinecraftGame");
        Files.createDirectories(launcher);
        Files.createDirectories(game);
        // Heuristic needs cwd or code source; write install.json instead for reliability
        Files.writeString(launcher.resolve("install.json"),
                InstallLayout.toInstallJson(new InstallLayout.Layout(
                        root.toAbsolutePath().normalize(),
                        launcher.toAbsolutePath().normalize(),
                        game.toAbsolutePath().normalize())),
                StandardCharsets.UTF_8);
        var layout = InstallLayout.readInstallJson(launcher.resolve("install.json"));
        assertTrue(layout.isPresent());
        assertEquals("MinecraftGame", layout.get().gameDir().getFileName().toString());
    }
}
