package com.blacklauncher.core.config;

import com.blacklauncher.auth.AuthSecretsStore;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.UuidUtil;
import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and persists {@link AppConfig} with corrupt-file fault tolerance.
 */
public class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigManager.class);

    private AppConfig config = new AppConfig();

    public AppConfig getConfig() {
        return config;
    }

    public void load() {
        Path path = AppPaths.configFile();
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                if (json == null || json.isBlank()) {
                    throw new IllegalStateException("配置文件为空");
                }
                AppConfig loaded = JsonUtil.fromJson(json, AppConfig.class);
                if (loaded == null) {
                    throw new IllegalStateException("配置 JSON 解析为 null");
                }
                config = loaded;
            }
            AuthSecretsStore.loadInto(config);
            normalize(config);
            AppPaths.applyFromConfig(config);
            saveQuiet();
        } catch (Exception e) {
            LOG.error("配置损坏或无法读取，已重置为默认配置", e);
            quarantineCorruptConfig(path);
            config = new AppConfig();
            AuthSecretsStore.loadInto(config);
            normalize(config);
            AppPaths.applyFromConfig(config);
            saveQuiet();
        }
    }

    public void save() {
        try {
            normalize(config);
            AuthSecretsStore.saveFrom(config);
            // Public config.json without tokens (HMCL-style split).
            String refresh = config.getMicrosoftRefreshToken();
            String access = config.getMinecraftAccessToken();
            String xuid = config.getXboxUserHash();
            long expires = config.getTokenExpiresAt();
            AuthSecretsStore.stripSecrets(config);
            try {
                String json = JsonUtil.toJsonPretty(config);
                Path path = AppPaths.configFile();
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                config.setMicrosoftRefreshToken(refresh);
                config.setMinecraftAccessToken(access);
                config.setXboxUserHash(xuid);
                config.setTokenExpiresAt(expires);
            }
        } catch (Exception e) {
            LOG.error("Failed to save config", e);
            try {
                normalize(config);
                AuthSecretsStore.saveFrom(config);
                String refresh = config.getMicrosoftRefreshToken();
                String access = config.getMinecraftAccessToken();
                String xuid = config.getXboxUserHash();
                long expires = config.getTokenExpiresAt();
                AuthSecretsStore.stripSecrets(config);
                try {
                    Files.writeString(AppPaths.configFile(), JsonUtil.toJsonPretty(config), StandardCharsets.UTF_8);
                } finally {
                    config.setMicrosoftRefreshToken(refresh);
                    config.setMinecraftAccessToken(access);
                    config.setXboxUserHash(xuid);
                    config.setTokenExpiresAt(expires);
                }
            } catch (Exception e2) {
                LOG.error("Config save retry also failed", e2);
                throw new IllegalStateException("保存配置失败", e2);
            }
        }
    }

    private void saveQuiet() {
        try {
            save();
        } catch (Exception e) {
            LOG.warn("Quiet config save failed", e);
        }
    }

    private void quarantineCorruptConfig(Path path) {
        try {
            if (path != null && Files.isRegularFile(path)) {
                Path bak = path.resolveSibling("config.corrupt." + System.currentTimeMillis() + ".json");
                Files.move(path, bak, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Quarantined corrupt config to {}", bak);
            }
        } catch (Exception e) {
            LOG.warn("Failed to quarantine corrupt config", e);
        }
    }

    private void normalize(AppConfig c) {
        if (!Validation.isNonBlank(c.getPlayerName())) {
            c.setPlayerName("Player");
        }
        c.setPlayerName(Validation.sanitizePlayerName(c.getPlayerName()));
        if (!Validation.isNonBlank(c.getPlayerUuid())) {
            c.setPlayerUuid(UuidUtil.offlineUuid(c.getPlayerName()));
        }
        if (c.getMinMemoryMb() < 256) {
            c.setMinMemoryMb(256);
        }
        if (c.getMaxMemoryMb() < c.getMinMemoryMb()) {
            c.setMaxMemoryMb(c.getMinMemoryMb());
        }
        if (c.getGameWidth() < 640) {
            c.setGameWidth(640);
        }
        if (c.getGameHeight() < 360) {
            c.setGameHeight(360);
        }
        if (!Validation.isNonBlank(c.getTheme())) {
            c.setTheme("dark");
        }
        if (!Validation.isNonBlank(c.getLanguage())) {
            c.setLanguage("zh_CN");
        }
        if (!Validation.isNonBlank(c.getDownloadSource())) {
            c.setDownloadSource("bmclapi");
        }
        if (c.getDownloadThreads() < 4) {
            c.setDownloadThreads(4);
        }
        if (c.getDownloadThreads() > 128) {
            c.setDownloadThreads(128);
        }
        if (!Validation.isNonBlank(c.getGameLanguage())) {
            c.setGameLanguage("zh_CN");
        }
    }
}
