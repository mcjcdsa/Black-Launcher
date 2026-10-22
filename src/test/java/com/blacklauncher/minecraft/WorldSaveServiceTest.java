package com.blacklauncher.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSaveServiceTest {

    @TempDir
    Path temp;

    @Test
    void detectsLegacyDimLayout() throws Exception {
        Path world = temp.resolve("MyWorld");
        Files.createDirectories(world.resolve("DIM-1").resolve("region"));
        Files.createDirectories(world.resolve("region"));
        writeMinimalGzip(world.resolve("level.dat"));

        assertEquals(WorldSaveFormat.LEGACY_DIM, WorldSaveService.detectFormat(world));
        assertTrue(ContentPackValidator.validateWorldFolder(world).valid());
    }

    @Test
    void detectsDimensionsTreeLayout() throws Exception {
        Path world = temp.resolve("NewWorld");
        Files.createDirectories(world.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"));
        writeMinimalGzip(world.resolve("level.dat"));

        assertEquals(WorldSaveFormat.DIMENSIONS_TREE, WorldSaveService.detectFormat(world));
    }

    @Test
    void rejectsWorldWithoutLevelDat() {
        Path world = temp.resolve("Broken");
        assertFalse(ContentPackValidator.validateWorldFolder(world).valid());
    }

    @Test
    void validatesResourcePackMeta() throws Exception {
        Path pack = temp.resolve("pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"t\"}}");
        assertTrue(ContentPackValidator.validateResourcePack(pack).valid());
    }

    private static void writeMinimalGzip(Path file) throws Exception {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        }
    }
}
