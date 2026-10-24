package com.blacklauncher.launch;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ensures the selected JRE meets {@code javaVersion.majorVersion} from the version JSON (HMCL-style gate).
 */
public final class JavaVersionProbe {

    private static final Logger LOG = LoggerFactory.getLogger(JavaVersionProbe.class);

    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "(?:version\\s+\")?(\\d+)(?:\\.(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARCH_MODEL = Pattern.compile(
            "sun\\.arch\\.data\\.model\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private JavaVersionProbe() {
    }

    public static void ensureCompatible(String configuredJavaPath, VersionJson version) throws LauncherException {
        int required = requiredMajor(version);
        if (required <= 0) {
            return;
        }
        String exe = JavaFinder.resolveJavaExecutable(configuredJavaPath);
        int actual = probeMajor(exe);
        if (actual <= 0) {
            throw new LauncherException("无法检测 Java 版本（" + exe + "）。请在设置中指定可用的 JDK/JRE。");
        }
        if (actual < required) {
            throw new LauncherException("当前 Java " + actual + " 过低，该版本需要 Java "
                    + required + "+。请在设置中更换 Java 路径。");
        }
    }

    /**
     * Detects whether the given Java executable is 64-bit.
     * Defaults to {@code true} when detection fails (unknown).
     */
    public static boolean is64Bit(String javaExecutable) {
        if (!Validation.isNonBlank(javaExecutable)) {
            return true;
        }
        String pathLower = javaExecutable.toLowerCase(Locale.ROOT);
        if (pathLower.contains("\\x86\\") || pathLower.contains("/x86/")
                || pathLower.contains("(x86)")) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExecutable, "-XshowSettings:properties", "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    Matcher m = ARCH_MODEL.matcher(line);
                    if (m.find()) {
                        p.destroyForcibly();
                        return !"32".equals(m.group(1));
                    }
                }
            }
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            Matcher m = ARCH_MODEL.matcher(out);
            if (m.find()) {
                return !"32".equals(m.group(1));
            }
        } catch (Exception e) {
            LOG.debug("is64Bit probe failed for {}: {}", javaExecutable, e.getMessage());
        }
        return true;
    }

    public static int requiredMajor(VersionJson version) {
        if (version == null || version.getJavaVersion() == null) {
            return 0;
        }
        return Math.max(0, version.getJavaVersion().getMajorVersion());
    }

    public static int probeMajor(String javaExecutable) {
        String line = JavaFinder.probeVersion(javaExecutable);
        if (!Validation.isNonBlank(line)) {
            return 0;
        }
        // Examples: openjdk version "17.0.9" / java version "1.8.0_392"
        Matcher m = VERSION_TOKEN.matcher(line);
        if (!m.find()) {
            return 0;
        }
        int major = Integer.parseInt(m.group(1));
        if (major == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2));
        }
        return major;
    }
}
