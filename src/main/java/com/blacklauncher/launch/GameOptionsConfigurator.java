package com.blacklauncher.launch;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.GameProcessGuard;
import com.blacklauncher.minecraft.MinecraftLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes / updates Minecraft options.txt (e.g. language) for an instance.
 */
public final class GameOptionsConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(GameOptionsConfigurator.class);

    private GameOptionsConfigurator() {
    }

    public static void applyIfEnabled(AppConfig config, String versionId) {
        if (config == null || !config.isAutoConfigureGameLanguage()) {
            return;
        }
        if (!Validation.isSafeVersionId(versionId)) {
            return;
        }
        Path gameDir = MinecraftLayout.forVersion(config, versionId).instanceRoot();
        applyLanguage(gameDir, config.getGameLanguage());
    }

    public static void applyLanguage(Path gameDir, String languageCode) {
        if (gameDir == null) {
            return;
        }
        try {
            GameProcessGuard.get().requireWritable(DirectoryKind.OPTIONS_TXT);
        } catch (Exception e) {
            LOG.warn("Skip options.txt write: {}", e.getMessage());
            return;
        }
        String lang = toMinecraftLang(languageCode);
        Path options = gameDir.resolve("options.txt");
        try {
            Files.createDirectories(gameDir);
            Map<String, String> map = new LinkedHashMap<>();
            if (Files.isRegularFile(options)) {
                List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
                for (String line : lines) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        map.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
            }
            map.put("lang", lang);
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                out.add(entry.getKey() + ":" + entry.getValue());
            }
            Files.write(options, out, StandardCharsets.UTF_8);
            LOG.info("Configured game language {} -> {}", lang, options);
        } catch (Exception e) {
            LOG.warn("Failed to write options.txt in {}", gameDir, e);
        }
    }

    /**
     * Converts launcher codes (zh_CN) to Minecraft options.txt codes (zh_cn).
     */
    public static String toMinecraftLang(String languageCode) {
        if (!Validation.isNonBlank(languageCode)) {
            return "zh_cn";
        }
        return languageCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
