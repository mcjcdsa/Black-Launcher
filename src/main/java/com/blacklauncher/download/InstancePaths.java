package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.McVersionUtil;
import com.blacklauncher.core.util.Validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Resolves per-version game instance directories (mods / shaderpacks / ...).
 */
public final class InstancePaths {

    public enum ContentFolder {
        MODS("mods", "Mod"),
        SHADERPACKS("shaderpacks", "光影"),
        RESOURCEPACKS("resourcepacks", "资源包"),
        SAVES("saves", "存档"),
        CONFIG("config", "配置");

        private final String dirName;
        private final String label;

        ContentFolder(String dirName, String label) {
            this.dirName = dirName;
            this.label = label;
        }

        public String dirName() {
            return dirName;
        }

        public String label() {
            return label;
        }
    }

    private InstancePaths() {
    }

    public static Path instanceRoot(AppConfig config, String versionId) {
        Validation.requireSafeVersionId(versionId);
        return com.blacklauncher.minecraft.MinecraftLayout.forVersion(config, versionId).instanceRoot();
    }

    public static Path contentDir(AppConfig config, String versionId, ContentFolder folder) {
        return instanceRoot(config, versionId).resolve(folder.dirName());
    }

    public static List<String> listInstalledVersionIds() {
        List<String> ids = new ArrayList<>();
        Path versionsDir = AppPaths.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            return ids;
        }
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(Validation::isSafeVersionId)
                    .filter(id -> Files.isRegularFile(versionsDir.resolve(id).resolve(id + ".json")))
                    .sorted(Comparator.naturalOrder())
                    .forEach(ids::add);
        } catch (Exception ignored) {
            // best effort
        }
        return ids;
    }

    /**
     * Picks the best default instance for a Modrinth install (e.g. 1.20.1 + fabric).
     */
    public static String resolveDefaultInstanceId(AppConfig config, String mcVersion, String loader) {
        List<String> installed = listInstalledVersionIds();
        String selected = config == null ? null : config.getSelectedVersion();

        String best = scoreBest(installed, mcVersion, loader, selected);
        if (Validation.isSafeVersionId(best)) {
            return best;
        }
        if (Validation.isSafeVersionId(selected)) {
            return selected;
        }
        if (Validation.isSafeVersionId(mcVersion)) {
            return mcVersion;
        }
        String extracted = McVersionUtil.extractBaseVersion(selected);
        if (Validation.isSafeVersionId(extracted)) {
            return extracted;
        }
        return installed.isEmpty() ? "unknown" : installed.get(installed.size() - 1);
    }

    private static String scoreBest(List<String> installed, String mcVersion, String loader, String selected) {
        String mc = Validation.isNonBlank(mcVersion) ? mcVersion.trim() : "";
        String ld = Validation.isNonBlank(loader) ? loader.trim().toLowerCase(Locale.ROOT) : "";
        if ("全部".equals(ld) || "all".equals(ld)) {
            ld = "";
        }

        String best = null;
        int bestScore = -1;
        for (String id : installed) {
            int score = 0;
            String lower = id.toLowerCase(Locale.ROOT);
            if (Validation.isNonBlank(mc) && (id.equals(mc) || lower.contains(mc.toLowerCase(Locale.ROOT)))) {
                score += 50;
            }
            if (Validation.isNonBlank(ld)) {
                if (lower.contains(ld)) {
                    score += 40;
                } else if ("fabric".equals(ld) && lower.contains("quilt")) {
                    score += 5;
                } else if ("forge".equals(ld) && lower.contains("neoforge")) {
                    score += 5;
                }
            }
            if (Validation.isNonBlank(selected) && id.equals(selected)) {
                score += 30;
            }
            // Prefer loader profiles over plain vanilla when a loader was requested.
            if (Validation.isNonBlank(ld) && id.equals(mc)) {
                score -= 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return bestScore > 0 ? best : null;
    }
}
