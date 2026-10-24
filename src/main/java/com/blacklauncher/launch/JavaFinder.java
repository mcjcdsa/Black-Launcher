package com.blacklauncher.launch;

import com.blacklauncher.core.util.Validation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Locates a usable Java executable on the system.
 */
public final class JavaFinder {

    public record JavaInstall(String executable, int major, boolean sixtyFourBit, String versionLine) {
    }

    private JavaFinder() {
    }

    /**
     * Discovers candidate Java installs and probes major / arch / version line.
     * Sorted by major version descending.
     */
    public static List<JavaInstall> discoverInstalls() {
        List<JavaInstall> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : discoverCandidates()) {
            if (!seen.add(candidate)) {
                continue;
            }
            if (!Files.isRegularFile(Paths.get(candidate))) {
                continue;
            }
            int major = JavaVersionProbe.probeMajor(candidate);
            boolean sixtyFour = JavaVersionProbe.is64Bit(candidate);
            String line = probeVersion(candidate);
            out.add(new JavaInstall(candidate, major, sixtyFour, line == null ? "" : line));
        }
        out.sort(Comparator.comparingInt(JavaInstall::major).reversed()
                .thenComparing((JavaInstall j) -> !j.sixtyFourBit())
                .thenComparing(JavaInstall::executable));
        return out;
    }

    public static String resolveJavaExecutable(String configuredPath) {
        if (Validation.isNonBlank(configuredPath)) {
            Path path = Paths.get(configuredPath.trim());
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().toString();
            }
            Path binJava = path.resolve("bin").resolve(javaFileName());
            if (Files.isRegularFile(binJava)) {
                return binJava.toAbsolutePath().toString();
            }
        }

        String javaHome = System.getProperty("java.home");
        if (Validation.isNonBlank(javaHome)) {
            Path current = Paths.get(javaHome, "bin", javaFileName());
            if (Files.isRegularFile(current)) {
                return current.toAbsolutePath().toString();
            }
        }

        for (String candidate : discoverCandidates()) {
            if (Files.isRegularFile(Paths.get(candidate))) {
                return candidate;
            }
        }
        return "java";
    }

    public static List<String> discoverCandidates() {
        Set<String> result = new LinkedHashSet<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String part : pathEnv.split(java.util.regex.Pattern.quote(
                    System.getProperty("path.separator")))) {
                Path java = Paths.get(part, javaFileName());
                if (Files.isRegularFile(java)) {
                    result.add(java.toAbsolutePath().toString());
                }
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                scanJavaHomes(Paths.get(programFiles, "Java"), result);
                scanJavaHomes(Paths.get(programFiles, "Eclipse Adoptium"), result);
                scanJavaHomes(Paths.get(programFiles, "Microsoft"), result);
            }
        } else {
            scanJavaHomes(Paths.get("/usr/lib/jvm"), result);
            scanJavaHomes(Paths.get("/Library/Java/JavaVirtualMachines"), result);
        }
        return new ArrayList<>(result);
    }

    public static String probeVersion(String javaExecutable) {
        try {
            Process process = new ProcessBuilder(javaExecutable, "-version")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void scanJavaHomes(Path root, Set<String> result) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path java = dir.resolve("bin").resolve(javaFileName());
                if (Files.isRegularFile(java)) {
                    result.add(java.toAbsolutePath().toString());
                }
                Path homeJava = dir.resolve("Contents").resolve("Home").resolve("bin").resolve(javaFileName());
                if (Files.isRegularFile(homeJava)) {
                    result.add(homeJava.toAbsolutePath().toString());
                }
            });
        } catch (Exception ignored) {
            // best-effort discovery
        }
    }

    private static String javaFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "java.exe" : "java";
    }
}
