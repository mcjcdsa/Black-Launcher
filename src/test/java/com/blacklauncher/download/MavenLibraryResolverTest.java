package com.blacklauncher.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenLibraryResolverTest {

    @Test
    void mavenPathForFabricLoader() {
        assertEquals(
                "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
                MavenLibraryResolver.mavenPath("net.fabricmc:fabric-loader:0.19.5"));
    }

    @Test
    void encodesPlusInMixinVersionForUrlButKeepsFilesystemPath() {
        String coord = "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7";
        String path = MavenLibraryResolver.mavenPath(coord);
        assertTrue(path.contains("0.17.4+mixin.0.8.7"));
        assertFalse(path.contains("%2B"));

        String encoded = MavenLibraryResolver.encodeMavenUrlPath(path);
        assertTrue(encoded.contains("0.17.4%2Bmixin.0.8.7"), encoded);
        assertFalse(encoded.contains("0.17.4+mixin"), encoded);
    }
}
