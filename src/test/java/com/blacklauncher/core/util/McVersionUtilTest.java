package com.blacklauncher.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McVersionUtilTest {

    @Test
    void extractsFromPlainVersion() {
        assertEquals("1.20.1", McVersionUtil.extractBaseVersion("1.20.1"));
    }

    @Test
    void extractsFromFabricProfile() {
        assertEquals("1.20.1", McVersionUtil.extractBaseVersion("fabric-loader-0.16.9-1.20.1"));
    }
}
