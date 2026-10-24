package com.blacklauncher.moddev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateValidationTest {

    @Test
    void emptyModIdBecomesTemplateMod() {
        assertEquals(TemplateValidation.DEFAULT_MOD_ID, TemplateValidation.normalizeModId(""));
        assertEquals(TemplateValidation.DEFAULT_MOD_ID, TemplateValidation.normalizeModId(null));
        assertNull(TemplateValidation.modIdViolation(""));
        assertNull(TemplateValidation.modIdViolation(null));
    }

    @Test
    void acceptsValidModIds() {
        assertTrue(TemplateValidation.isValidModId("template-mod"));
        assertTrue(TemplateValidation.isValidModId("my_mod"));
        assertTrue(TemplateValidation.isValidModId("a"));
        assertNull(TemplateValidation.modIdViolation("cool-mod_1"));
    }

    @Test
    void rejectsInvalidModIds() {
        assertFalse(TemplateValidation.isValidModId("1bad"));
        assertFalse(TemplateValidation.isValidModId("Bad"));
        assertFalse(TemplateValidation.isValidModId("has space"));
        assertNotNull(TemplateValidation.modIdViolation("1bad"));
        assertNotNull(TemplateValidation.modIdViolation("UPPER"));
    }

    @Test
    void packageNameRules() {
        assertTrue(TemplateValidation.isValidPackageName("com.example.mod"));
        assertTrue(TemplateValidation.isValidPackageName("_foo.bar"));
        assertFalse(TemplateValidation.isValidPackageName(""));
        assertFalse(TemplateValidation.isValidPackageName("com.1bad"));
        assertFalse(TemplateValidation.isValidPackageName("com.example-mod"));
        assertNotNull(TemplateValidation.packageNameViolation(""));
        assertNull(TemplateValidation.packageNameViolation("com.example"));
    }

    @Test
    void validateRequest() {
        assertNull(TemplateValidation.validate(new TemplateRequest(
                "Example", "", "com.example.mod", "1.21.4",
                false, false, false, false)));
        assertNotNull(TemplateValidation.validate(new TemplateRequest(
                "", "ok-mod", "com.example", "1.21", false, false, false, false)));
        assertNotNull(TemplateValidation.validate(new TemplateRequest(
                "X", "ok-mod", "bad-pkg", "1.21", false, false, false, false)));
    }
}
