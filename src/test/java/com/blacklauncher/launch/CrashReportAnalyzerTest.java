package com.blacklauncher.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportAnalyzerTest {

    @Test
    void detectsOutOfMemory() {
        String advice = CrashReportAnalyzer.summarize("java.lang.OutOfMemoryError: Java heap space");
        assertTrue(advice.contains("内存"));
    }

    @Test
    void detectsWrongJava() {
        String advice = CrashReportAnalyzer.summarize(
                "java.lang.UnsupportedClassVersionError: has been compiled by a more recent version");
        assertTrue(advice.contains("Java"));
    }

    @Test
    void emptyLogYieldsEmpty() {
        assertTrue(CrashReportAnalyzer.summarize("").isEmpty());
        assertFalse(CrashReportAnalyzer.looksLikeJvmInitFailure("normal start"));
    }

    @Test
    void detectsOptifineConflict() {
        String advice = CrashReportAnalyzer.summarize("Invalid Services found OptiFine");
        assertTrue(advice.contains("OptiFine"));
    }
}
