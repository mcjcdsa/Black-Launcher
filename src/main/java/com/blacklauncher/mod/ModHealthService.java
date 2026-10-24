package com.blacklauncher.mod;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.HashUtil;
import com.blacklauncher.core.util.McVersionUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.LocalModEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Best-effort Modrinth update / missing-dependency hints for local mod jars.
 */
public final class ModHealthService {

    public record UpdateHint(
            Path file,
            String fileName,
            String currentVersion,
            String latestVersion,
            String projectTitle,
            String downloadUrl,
            String newFileName
    ) {
    }

    private final ModrinthClient client;

    public ModHealthService(AppConfig config) {
        this.client = new ModrinthClient(config);
    }

    public ModrinthClient client() {
        return client;
    }

    /**
     * For each enabled jar, look up Modrinth by SHA-1 and compare against newest matching version.
     */
    public List<UpdateHint> checkUpdates(AppConfig config, String versionId, List<LocalModEntry> mods) {
        List<UpdateHint> hints = new ArrayList<>();
        if (mods == null || mods.isEmpty()) {
            return hints;
        }
        String gameVersion = McVersionUtil.extractBaseVersion(versionId);
        String loader = inferLoader(versionId);

        for (LocalModEntry mod : mods) {
            if (mod == null || mod.status() == LocalModEntry.Status.DISABLED) {
                continue;
            }
            String lower = mod.fileName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) {
                continue;
            }
            try {
                String sha1 = HashUtil.sha1(mod.path());
                Optional<ModrinthClient.ModVersion> currentOpt = client.versionByHash(sha1);
                if (currentOpt.isEmpty()) {
                    continue;
                }
                ModrinthClient.ModVersion current = currentOpt.get();
                String projectId = current.projectId();
                if (!Validation.isNonBlank(projectId)) {
                    continue;
                }
                List<ModrinthClient.ModVersion> versions = client.projectVersions(projectId, gameVersion, loader);
                if (versions.isEmpty() && Validation.isNonBlank(loader)) {
                    versions = client.projectVersions(projectId, gameVersion, null);
                }
                if (versions.isEmpty()) {
                    continue;
                }
                ModrinthClient.ModVersion latest = versions.get(0);
                if (latest.id().equals(current.id())) {
                    continue;
                }
                String latestSha1 = latest.primaryFile() != null ? latest.primaryFile().sha1() : "";
                if (Validation.isNonBlank(latestSha1) && latestSha1.equalsIgnoreCase(sha1)) {
                    continue;
                }
                String title = projectId;
                try {
                    title = client.getProject(projectId).title();
                    if (!Validation.isNonBlank(title)) {
                        title = projectId;
                    }
                } catch (Exception ignored) {
                }
                ModrinthClient.ModFile file = latest.primaryFile();
                if (file == null || !Validation.isNonBlank(file.url())) {
                    continue;
                }
                hints.add(new UpdateHint(
                        mod.path(),
                        mod.fileName(),
                        blank(current.versionNumber(), current.name()),
                        blank(latest.versionNumber(), latest.name()),
                        title,
                        file.url(),
                        file.fileName()
                ));
            } catch (Exception ignored) {
                // best-effort: skip unreadable / network failures per file
            }
        }
        return hints;
    }

    /**
     * Scan required Modrinth dependencies that appear missing among local enabled jars (by project id).
     */
    public List<String> missingDependencyHints(AppConfig config, String versionId, List<LocalModEntry> mods) {
        List<String> hints = new ArrayList<>();
        if (mods == null || mods.isEmpty()) {
            return hints;
        }
        Map<Path, ModrinthClient.ModVersion> byPath = new HashMap<>();
        Set<String> presentProjects = new HashSet<>();

        for (LocalModEntry mod : mods) {
            if (mod == null || mod.status() == LocalModEntry.Status.DISABLED) {
                continue;
            }
            String lower = mod.fileName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) {
                continue;
            }
            try {
                String sha1 = HashUtil.sha1(mod.path());
                Optional<ModrinthClient.ModVersion> opt = client.versionByHash(sha1);
                if (opt.isEmpty()) {
                    continue;
                }
                ModrinthClient.ModVersion v = opt.get();
                byPath.put(mod.path(), v);
                if (Validation.isNonBlank(v.projectId())) {
                    presentProjects.add(v.projectId());
                }
            } catch (Exception ignored) {
            }
        }

        Set<String> reported = new HashSet<>();
        for (Map.Entry<Path, ModrinthClient.ModVersion> entry : byPath.entrySet()) {
            ModrinthClient.ModVersion version = entry.getValue();
            if (version.dependencies() == null) {
                continue;
            }
            String owner = Validation.isNonBlank(version.name()) ? version.name() : entry.getKey().getFileName().toString();
            for (ModrinthClient.ModDependency dep : version.dependencies()) {
                if (dep == null || !dep.required() || !Validation.isNonBlank(dep.projectId())) {
                    continue;
                }
                if (presentProjects.contains(dep.projectId()) || !reported.add(dep.projectId())) {
                    continue;
                }
                String depTitle = dep.projectId();
                try {
                    String t = client.getProject(dep.projectId()).title();
                    if (Validation.isNonBlank(t)) {
                        depTitle = t;
                    }
                } catch (Exception ignored) {
                }
                hints.add("「" + owner + "」缺少必需依赖：" + depTitle);
            }
        }
        return hints;
    }

    static String inferLoader(String versionId) {
        if (!Validation.isNonBlank(versionId)) {
            return null;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) {
            return "neoforge";
        }
        if (lower.contains("fabric")) {
            return "fabric";
        }
        if (lower.contains("quilt")) {
            return "quilt";
        }
        if (lower.contains("forge")) {
            return "forge";
        }
        return null;
    }

    private static String blank(String primary, String fallback) {
        if (Validation.isNonBlank(primary)) {
            return primary.trim();
        }
        return Validation.isNonBlank(fallback) ? fallback.trim() : "?";
    }
}
