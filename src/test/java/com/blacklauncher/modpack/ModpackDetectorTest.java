package com.blacklauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModpackDetectorTest {

    @TempDir
    Path temp;

    @Test
    void detectsModrinthIndex() throws Exception {
        Path zip = temp.resolve("pack.mrpack");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("modrinth.index.json"));
            String json = """
                    {
                      "formatVersion": 1,
                      "game": "minecraft",
                      "versionId": "1.0",
                      "name": "TestPack",
                      "files": [],
                      "dependencies": {
                        "minecraft": "1.20.1",
                        "fabric-loader": "0.15.0"
                      }
                    }
                    """;
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ModpackDescriptor d = ModpackDetector.detect(zip);
        assertEquals(ModpackFormat.MODRINTH, d.getFormat());
        assertEquals("1.20.1", d.getMcVersion());
        assertEquals("TestPack", d.getName());
    }

    @Test
    void rejectsUnknown() throws Exception {
        Path zip = temp.resolve("noise.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("hi".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(Exception.class, () -> ModpackDetector.detect(zip));
    }
}
