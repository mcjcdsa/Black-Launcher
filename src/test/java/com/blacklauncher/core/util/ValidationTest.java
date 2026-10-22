package com.blacklauncher.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    @Test
    void sanitizePlayerNameKeepsValidNames() {
        assertEquals("Steve_123", Validation.sanitizePlayerName("Steve_123"));
    }

    @Test
    void sanitizePlayerNameRejectsInvalid() {
        assertEquals("Player", Validation.sanitizePlayerName("!!"));
        assertEquals("Player", Validation.sanitizePlayerName(""));
    }

    @Test
    void safeVersionId() {
        assertTrue(Validation.isSafeVersionId("1.20.1"));
        assertTrue(Validation.isSafeVersionId("24w14a"));
        assertFalse(Validation.isSafeVersionId("../evil"));
        assertFalse(Validation.isSafeVersionId("a/b"));
    }
}
