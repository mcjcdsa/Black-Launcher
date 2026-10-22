package com.blacklauncher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameOptionsConfiguratorTest {

    @TempDir
    Path temp;

    @Test
    void writesChineseLangByDefaultMapping() throws Exception {
        GameOptionsConfigurator.applyLanguage(temp, "zh_CN");
        Path options = temp.resolve("options.txt");
        assertTrue(Files.isRegularFile(options));
        String content = Files.readString(options, StandardCharsets.UTF_8);
        assertTrue(content.contains("lang:zh_cn"));
    }

    @Test
    void mapsCodes() {
        assertEquals("zh_cn", GameOptionsConfigurator.toMinecraftLang("zh_CN"));
        assertEquals("en_us", GameOptionsConfigurator.toMinecraftLang("en_US"));
    }
}
