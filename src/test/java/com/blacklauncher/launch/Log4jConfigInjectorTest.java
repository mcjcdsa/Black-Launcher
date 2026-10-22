package com.blacklauncher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Log4jConfigInjectorTest {

    @TempDir
    Path temp;

    @Test
    void writesConfigAndAddsJvmArg() throws Exception {
        Path file = Log4jConfigInjector.ensureConfig(temp);
        assertTrue(file != null && Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("nolookups"));

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        Log4jConfigInjector.applyJvmArg(cmd, file);
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("-Dlog4j.configurationFile=")));
    }
}