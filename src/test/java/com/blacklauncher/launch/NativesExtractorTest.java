package com.blacklauncher.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativesExtractorTest {

    @Test
    void detectsModernNativesClassifier() {
        assertTrue(NativesExtractor.isNativeLibraryName(
                "org.lwjgl:lwjgl:3.3.3:natives-windows", "natives-windows"));
        assertTrue(NativesExtractor.isNativeLibraryName(
                "org.lwjgl:lwjgl-opengl:3.3.3:natives-linux", "natives-linux"));
        assertFalse(NativesExtractor.isNativeLibraryName(
                "org.lwjgl:lwjgl:3.3.3", "natives-windows"));
        assertFalse(NativesExtractor.isNativeLibraryName(
                "org.lwjgl:lwjgl:3.3.3:natives-macos", "natives-windows"));
    }

    @Test
    void arm64KeyAlsoMatchesBaseWindowsNatives() {
        assertTrue(NativesExtractor.isNativeLibraryName(
                "org.lwjgl:lwjgl:3.3.3:natives-windows", "natives-windows-arm64"));
    }
}
