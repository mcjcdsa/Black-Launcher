package com.blacklauncher.download;

import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameFolderImportServiceTest {

    @TempDir
    Path temp;

    private String previousUserHome;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        Path fakeHome = temp.resolve("home");
        Files.createDirectories(fakeHome);
        System.setProperty("user.home", fakeHome.toString());
        AppPaths.ensureDirectories();
        database = new Database();
        database.open();
        database.migrate();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        if (previousUserHome != null) {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void scansMinecraftRootAndImportsVersion() throws Exception {
        Path mc = temp.resolve("fake-minecraft");
        Path versionDir = mc.resolve("versions").resolve("1.20.1");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("1.20.1.json"), """
                {"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main"}
                """, StandardCharsets.UTF_8);
        Files.write(versionDir.resolve("1.20.1.jar"), new byte[]{1, 2, 3, 4});
        Files.createDirectories(mc.resolve("mods"));
        Files.write(mc.resolve("mods").resolve("demo.jar"), new byte[]{9});
        Files.createDirectories(mc.resolve("libraries").resolve("demo"));
        Files.write(mc.resolve("libraries").resolve("demo").resolve("a.jar"), new byte[]{5});

        GameFolderImportService service = new GameFolderImportService(database);
        GameFolderImportService.ScanResult scan = service.scan(mc);

        assertEquals(GameFolderImportService.RootKind.MINECRAFT_ROOT, scan.kind());
        assertEquals(1, scan.versions().size());
        assertEquals("1.20.1", scan.versions().get(0).id());
        assertTrue(scan.versions().get(0).hasJar());
        assertTrue(scan.versions().get(0).modCount() >= 1);
        assertTrue(scan.hasLibraries());

        GameFolderImportService.ImportResult result = service.importSelected(
                scan, scan.versions(), GameFolderImportService.ImportOptions.defaults());

        assertEquals(1, result.imported());
        assertTrue(Files.isRegularFile(AppPaths.versionsDir().resolve("1.20.1").resolve("1.20.1.json")));
        assertTrue(Files.isRegularFile(AppPaths.versionsDir().resolve("1.20.1").resolve("1.20.1.jar")));
        assertTrue(Files.isRegularFile(AppPaths.librariesDir().resolve("demo").resolve("a.jar")));
        assertTrue(Files.isRegularFile(
                AppPaths.gameDir().resolve("instances").resolve("1.20.1").resolve("mods").resolve("demo.jar")));
    }

    @Test
    void recognizesFabricProfileWithoutOwnJarWhenParentPresent() throws Exception {
        Path versions = temp.resolve("versions");
        Path vanilla = versions.resolve("1.20.1");
        Path fabric = versions.resolve("fabric-loader-1.20.1");
        Files.createDirectories(vanilla);
        Files.createDirectories(fabric);
        Files.writeString(vanilla.resolve("1.20.1.json"),
                "{\"id\":\"1.20.1\",\"type\":\"release\"}", StandardCharsets.UTF_8);
        Files.write(vanilla.resolve("1.20.1.jar"), new byte[]{1});
        Files.writeString(fabric.resolve("fabric-loader-1.20.1.json"),
                "{\"id\":\"fabric-loader-1.20.1\",\"type\":\"release\",\"inheritsFrom\":\"1.20.1\"}",
                StandardCharsets.UTF_8);

        GameFolderImportService service = new GameFolderImportService(database);
        GameFolderImportService.ScanResult scan = service.scan(versions);

        assertEquals(2, scan.versions().size());
        GameFolderImportService.DetectedVersion fabricVer = scan.versions().stream()
                .filter(v -> v.id().startsWith("fabric"))
                .findFirst()
                .orElseThrow();
        assertFalse(fabricVer.hasJar());
        assertTrue(fabricVer.launchable());
    }
}
