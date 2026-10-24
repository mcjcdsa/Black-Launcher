package com.blacklauncher.launch;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.memory.MemoryProfile;
import com.blacklauncher.memory.MemoryProfileStore;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.model.VersionJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One-click remediation helpers used by the crash dialog.
 */
public final class LaunchQuickFixService {

    private LaunchQuickFixService() {
    }

    public static void applyLowerMemory(AppConfig config, String versionId) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        MemoryProfile profile = MemoryProfileStore.load(config, versionId);
        int xmx = Math.max(1024, profile.getXmxMb() * 2 / 3);
        profile.setXmxMb(xmx);
        profile.setXmsMb(512);
        MemoryProfileStore.save(config, versionId, profile);
        if (config != null) {
            config.setMaxMemoryMb(Math.max(config.getMinMemoryMb(), xmx));
        }
    }

    public static Optional<Path> disableNewestMod(AppConfig config, String versionId) throws Exception {
        Validation.requireSafeVersionId(versionId);
        Path modsDir = MinecraftLayout.forVersion(config, versionId).resolve(DirectoryKind.MODS);
        if (!Files.isDirectory(modsDir)) {
            return Optional.empty();
        }
        Path newest = null;
        FileTime newestTime = null;
        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar")) {
                    continue;
                }
                FileTime t = Files.getLastModifiedTime(p);
                if (newest == null || (t != null && (newestTime == null || t.compareTo(newestTime) > 0))) {
                    newest = p;
                    newestTime = t;
                }
            }
        }
        if (newest == null) {
            return Optional.empty();
        }
        Path disabled = newest.resolveSibling(newest.getFileName().toString() + ".disabled");
        Files.move(newest, disabled);
        return Optional.of(disabled);
    }

    public static Optional<JavaFinder.JavaInstall> suggestJavaForVersion(AppConfig config, String versionId)
            throws Exception {
        Validation.requireSafeVersionId(versionId);
        Path jsonPath = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            return Optional.empty();
        }
        VersionJson version = JsonUtil.fromJson(Files.readString(jsonPath, StandardCharsets.UTF_8), VersionJson.class);
        int required = JavaVersionProbe.requiredMajor(version);
        List<JavaFinder.JavaInstall> installs = JavaFinder.discoverInstalls();
        if (installs.isEmpty()) {
            return Optional.empty();
        }
        if (required <= 0) {
            return Optional.of(installs.get(0));
        }
        return installs.stream()
                .filter(j -> j.major() >= required)
                .min(Comparator
                        .comparingInt((JavaFinder.JavaInstall j) -> j.major() - required)
                        .thenComparing((JavaFinder.JavaInstall j) -> !j.sixtyFourBit())
                        .thenComparing(JavaFinder.JavaInstall::major));
    }
}
