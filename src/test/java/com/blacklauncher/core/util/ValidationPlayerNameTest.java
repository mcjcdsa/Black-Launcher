package com.blacklauncher.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationPlayerNameTest {

    @Test
    void acceptsMojangStyleNames() {
        assertTrue(Validation.isValidPlayerName("Steve"));
        assertTrue(Validation.isValidPlayerName("Player_01"));
        assertNull(Validation.playerNameViolation("Alex"));
    }

    @Test
    void rejectsIllegalNames() {
        assertFalse(Validation.isValidPlayerName(""));
        assertFalse(Validation.isValidPlayerName("ab"));
        assertFalse(Validation.isValidPlayerName("你好"));
        assertNotNull(Validation.playerNameViolation("a"));
        assertNotNull(Validation.playerNameViolation("this_name_is_way_too_long"));
        assertEquals("昵称仅允许英文字母、数字和下划线", Validation.playerNameViolation("bad-name"));
    }
}
