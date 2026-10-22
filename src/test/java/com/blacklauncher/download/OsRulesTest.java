package com.blacklauncher.download;

import com.blacklauncher.model.VersionJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsRulesTest {

    @Test
    void resolvesArchPlaceholder() {
        String resolved = OsRules.resolveClassifier("natives-windows-${arch}");
        assertTrue(resolved.startsWith("natives-windows-"));
        assertFalse(resolved.contains("${arch}"));
    }

    @Test
    void osVersionRegex() {
        VersionJson.Os os = new VersionJson.Os();
        os.setName("osx");
        os.setVersion("^10\\.");
        assertTrue(OsRules.osMatches(os, "osx", "x86_64", "10.15.7"));
        assertFalse(OsRules.osMatches(os, "osx", "x86_64", "14.0"));
        assertFalse(OsRules.osMatches(os, "windows", "amd64", "10.0"));
    }

    @Test
    void nativesKeyIsNonBlank() {
        assertTrue(OsRules.nativesClassifierKey().startsWith("natives-"));
        assertEquals(OsRules.mojangArch(), OsRules.mojangArch());
    }
}
