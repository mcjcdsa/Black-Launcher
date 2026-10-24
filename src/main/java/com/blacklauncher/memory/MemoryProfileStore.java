package com.blacklauncher.memory;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.MinecraftLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads / saves per-version {@code memory.json} under the instance root.
 */
public final class MemoryProfileStore {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryProfileStore.class);

    private MemoryProfileStore() {
    }

    public static Path path(AppConfig config, String versionId) {
        return MinecraftLayout.forVersion(config, versionId).instanceRoot().resolve("memory.json");
    }

    public static MemoryProfile load(AppConfig config, String versionId) {
        Path file = path(config, versionId);
        if (Files.isRegularFile(file)) {
            try {
                MemoryProfile profile = JsonUtil.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), MemoryProfile.class);
                if (profile != null) {
                    normalize(profile);
                    return profile;
                }
            } catch (Exception e) {
                LOG.warn("Failed to read memory.json for {}, seeding defaults: {}", versionId, e.getMessage());
            }
        }
        MemoryProfile seeded = seedFromConfig(config);
        try {
            save(config, versionId, seeded);
        } catch (Exception e) {
            LOG.debug("Could not seed memory.json for {}: {}", versionId, e.getMessage());
        }
        return seeded;
    }

    public static void save(AppConfig config, String versionId, MemoryProfile profile) throws LauncherException {
        if (profile == null) {
            throw new LauncherException("内存配置为空");
        }
        normalize(profile);
        Path file = path(config, versionId);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtil.toJsonPretty(profile), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new LauncherException("保存内存配置失败: " + e.getMessage(), e);
        }
    }

    public static MemoryProfile seedFromConfig(AppConfig config) {
        MemoryProfile p = new MemoryProfile();
        if (config != null) {
            p.setXmsMb(Math.max(512, config.getMinMemoryMb()));
            p.setXmxMb(Math.max(p.getXmsMb(), config.getMaxMemoryMb()));
            if (Validation.isNonBlank(config.getJvmArgs())) {
                p.setCustomJvmArgs(config.getJvmArgs().trim());
            }
        }
        return p;
    }

    private static void normalize(MemoryProfile profile) {
        if (!Validation.isNonBlank(profile.getSchemeId())) {
            profile.setSchemeId("balanced_g1");
        }
        if (profile.getCustomJvmArgs() == null) {
            profile.setCustomJvmArgs("");
        }
        if (profile.getXmsMb() < 512) {
            profile.setXmsMb(512);
        }
        if (profile.getXmxMb() < 512) {
            profile.setXmxMb(512);
        }
    }
}
