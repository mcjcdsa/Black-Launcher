package com.blacklauncher.auth;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists MSA tokens separately from public {@code config.json} (HMCL-style split).
 */
public final class AuthSecretsStore {

    private static final Logger LOG = LoggerFactory.getLogger(AuthSecretsStore.class);

    public static class Secrets {
        public String microsoftRefreshToken = "";
        public String minecraftAccessToken = "";
        public String xboxUserHash = "";
        public long tokenExpiresAt;
    }

    private AuthSecretsStore() {
    }

    public static Path secretsFile() {
        return AppPaths.home().resolve("account-secrets.json");
    }

    public static void loadInto(AppConfig config) {
        if (config == null) {
            return;
        }
        Path path = secretsFile();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Secrets s = JsonUtil.fromJson(Files.readString(path, StandardCharsets.UTF_8), Secrets.class);
            if (s == null) {
                return;
            }
            if (Validation.isNonBlank(s.microsoftRefreshToken)) {
                config.setMicrosoftRefreshToken(s.microsoftRefreshToken);
            }
            if (Validation.isNonBlank(s.minecraftAccessToken)) {
                config.setMinecraftAccessToken(s.minecraftAccessToken);
            }
            if (Validation.isNonBlank(s.xboxUserHash)) {
                config.setXboxUserHash(s.xboxUserHash);
            }
            if (s.tokenExpiresAt > 0) {
                config.setTokenExpiresAt(s.tokenExpiresAt);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load account-secrets.json", e);
        }
    }

    public static void saveFrom(AppConfig config) {
        if (config == null) {
            return;
        }
        Secrets s = new Secrets();
        s.microsoftRefreshToken = nullToEmpty(config.getMicrosoftRefreshToken());
        s.minecraftAccessToken = nullToEmpty(config.getMinecraftAccessToken());
        s.xboxUserHash = nullToEmpty(config.getXboxUserHash());
        s.tokenExpiresAt = config.getTokenExpiresAt();
        try {
            Path path = secretsFile();
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.writeString(tmp, JsonUtil.toJsonPretty(s), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.error("Failed to save account-secrets.json", e);
        }
    }

    /** Clear secret fields on a config copy before writing public config.json. */
    public static void stripSecrets(AppConfig config) {
        if (config == null) {
            return;
        }
        config.setMicrosoftRefreshToken("");
        config.setMinecraftAccessToken("");
        config.setXboxUserHash("");
        config.setTokenExpiresAt(0);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
