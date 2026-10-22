package com.blacklauncher.launch;

import com.blacklauncher.core.util.Validation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rewrites {@code -DignoreList=} for Forge/NeoForge {@code BootstrapLauncher} (HMCL-aligned).
 * <p>
 * Old BootstrapLauncher (&lt;0.1.17) matches ignore tokens as <b>substrings</b> of full classpath
 * paths (e.g. game dir {@code .../asm/...} drops every asm jar). We expand tokens to exact jar paths.
 * For 0.1.17+ (filename-only matching) we still ensure the primary jar file name is listed.
 */
public final class BootstrapIgnoreList {

    private BootstrapIgnoreList() {
    }

    public static void rewriteInPlace(List<String> command, String mainClass, Path clientJar, List<String> classpath) {
        if (command == null || command.isEmpty()) {
            return;
        }
        if (!Validation.isNonBlank(mainClass)
                || !mainClass.toLowerCase(Locale.ROOT).contains("bootstraplauncher")) {
            return;
        }
        String primaryName = clientJar == null ? null : clientJar.getFileName().toString();
        String primaryPath = clientJar == null ? null : clientJar.toAbsolutePath().toString();
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg == null || !arg.startsWith("-DignoreList=")) {
                continue;
            }
            String list = arg.substring("-DignoreList=".length());
            command.set(i, "-DignoreList=" + rewrite(list, primaryName, primaryPath, classpath));
        }
    }

    static String rewrite(String ignoreList, String primaryJarName, String primaryJarPath, List<String> classpath) {
        Set<String> out = new LinkedHashSet<>();
        if (Validation.isNonBlank(primaryJarPath)) {
            out.add(primaryJarPath);
        } else if (Validation.isNonBlank(primaryJarName)) {
            out.add(primaryJarName);
        }

        String[] tokens = ignoreList == null ? new String[0] : ignoreList.split(",");
        List<String> substrings = new ArrayList<>();
        for (String raw : tokens) {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            // Already an absolute/relative path — keep.
            if (t.contains("/") || t.contains("\\") || t.endsWith(".jar")) {
                out.add(t);
                continue;
            }
            if (Validation.isNonBlank(primaryJarName) && primaryJarName.equals(t)) {
                continue;
            }
            substrings.add(t);
        }

        if (classpath != null) {
            for (String cp : classpath) {
                if (!Validation.isNonBlank(cp)) {
                    continue;
                }
                Path p = Path.of(cp);
                String fileName = p.getFileName() == null ? cp : p.getFileName().toString();
                for (String sub : substrings) {
                    if (fileName.contains(sub)) {
                        out.add(p.toAbsolutePath().toString());
                        break;
                    }
                }
            }
        }

        // 0.1.17+ filename match: ensure primary jar name present.
        if (Validation.isNonBlank(primaryJarName)) {
            boolean hasName = out.stream().anyMatch(e -> e.equals(primaryJarName) || e.endsWith(primaryJarName)
                    || e.endsWith("/" + primaryJarName) || e.endsWith("\\" + primaryJarName));
            if (!hasName) {
                out.add(primaryJarName);
            }
        }
        return String.join(",", out);
    }
}
