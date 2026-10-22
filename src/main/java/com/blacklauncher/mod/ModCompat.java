package com.blacklauncher.mod;

import com.blacklauncher.core.util.McVersionUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.mod.ModrinthClient.ModVersion;
import com.blacklauncher.mod.ModrinthClient.ProjectDetail;

import java.util.List;
import java.util.Locale;

/**
 * Compatibility checks between Modrinth metadata and the active launcher instance.
 */
public final class ModCompat {

    private ModCompat() {
    }

    public record InstanceHint(String versionId, String mcVersion, String loader) {
        public boolean hasLoader() {
            return Validation.isNonBlank(loader);
        }
    }

    public static InstanceHint fromSelectedVersion(String selectedVersionId) {
        if (!Validation.isNonBlank(selectedVersionId)) {
            return new InstanceHint("", "", "");
        }
        String lower = selectedVersionId.toLowerCase(Locale.ROOT);
        String loader = "";
        if (lower.contains("neoforge")) {
            loader = "neoforge";
        } else if (lower.contains("forge")) {
            loader = "forge";
        } else if (lower.contains("quilt")) {
            loader = "quilt";
        } else if (lower.contains("fabric")) {
            loader = "fabric";
        }
        String mc = McVersionUtil.extractBaseVersion(selectedVersionId);
        return new InstanceHint(selectedVersionId, mc, loader);
    }

    public static String projectMismatchWarning(ProjectDetail project, InstanceHint instance) {
        if (project == null || instance == null || !Validation.isNonBlank(instance.versionId())) {
            return "";
        }
        boolean mcOk = !Validation.isNonBlank(instance.mcVersion())
                || project.gameVersions() == null
                || project.gameVersions().isEmpty()
                || containsIgnoreCase(project.gameVersions(), instance.mcVersion());
        boolean loaderOk = !instance.hasLoader()
                || project.loaders() == null
                || project.loaders().isEmpty()
                || containsIgnoreCase(project.loaders(), instance.loader());
        if (mcOk && loaderOk) {
            return "";
        }
        StringBuilder sb = new StringBuilder("当前实例「")
                .append(instance.versionId())
                .append("」与该模组可能不兼容。");
        if (!mcOk) {
            sb.append(" 实例游戏版本 ").append(instance.mcVersion())
                    .append(" 不在模组支持列表中。");
        }
        if (!loaderOk) {
            sb.append(" 实例加载器 ").append(instance.loader())
                    .append(" 与模组加载器不匹配（支持：")
                    .append(String.join(", ", project.loaders())).append("）。");
        }
        return sb.toString();
    }

    public static boolean versionMatchesInstance(ModVersion version, InstanceHint instance) {
        if (version == null || instance == null) {
            return true;
        }
        boolean mcOk = !Validation.isNonBlank(instance.mcVersion())
                || version.gameVersions() == null
                || version.gameVersions().isEmpty()
                || containsIgnoreCase(version.gameVersions(), instance.mcVersion());
        boolean loaderOk = !instance.hasLoader()
                || version.loaders() == null
                || version.loaders().isEmpty()
                || containsIgnoreCase(version.loaders(), instance.loader());
        return mcOk && loaderOk;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null || !Validation.isNonBlank(value)) {
            return false;
        }
        String needle = value.toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
